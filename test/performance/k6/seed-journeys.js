import http from 'k6/http';
import encoding from 'k6/encoding';
import {check} from 'k6';
import exec from 'k6/execution';
import {Counter} from 'k6/metrics';

const runId = __ENV.RUN_ID;
const endpoint = __ENV.OTLP_ENDPOINT || 'http://127.0.0.1:14318/v1/logs';
const recordCount = Number(__ENV.SEED_RECORD_COUNT || '500000');
const batchSize = Number(__ENV.SEED_BATCH_SIZE || '50');
const batches = Math.ceil(recordCount / batchSize);
const alphaUser = __ENV.SEED_ALPHA_USERNAME || 'sdk-partner-a';
const alphaPassword = __ENV.SEED_ALPHA_PASSWORD || 'local-synthetic-sdk-a';
const betaUser = __ENV.SEED_BETA_USERNAME || 'sdk-partner-b';
const betaPassword = __ENV.SEED_BETA_PASSWORD || 'local-synthetic-sdk-b';
const startedMillis = Date.now();
const serviceName = __ENV.SEED_SERVICE_NAME || 'partner-observability-performance-journey-seed';
export const recordsSeeded = new Counter('records_seeded');

export const options = {
  scenarios: {seed: {
    executor: 'constant-arrival-rate', rate: 25, timeUnit: '1s', duration: `${Math.ceil(batches / 25)}s`,
    preAllocatedVUs: 25, maxVUs: 100, gracefulStop: '10s',
  }},
  thresholds: {checks: ['rate==1'], records_seeded: [`count==${recordCount}`], dropped_iterations: ['count==0']},
};

function pad(value, length) { return value.toString().padStart(length, '0'); }
function uuid(value) {
  const hex = value.toString(16).padStart(12, '0');
  return `00000000-0000-4000-8000-${hex}`;
}
function attribute(key, value) { return {key, value: {stringValue: String(value)}}; }
function integerAttribute(key, value) { return {key, value: {intValue: String(value)}}; }
function identifier(prefix, partner, journey) {
  return `SYNTHETIC-${prefix}-${runId}-${partner}-${pad(journey, 8)}`.replace(/[^A-Za-z0-9._-]/g, '-').slice(0, 128);
}
function ageMillis(index) {
  // Loki accepts bounded out-of-order writes, not arbitrary backfill after a stream has advanced.
  // Submit the approved distribution oldest-to-newest: 20% at 8-16 days, 30% at 1-8 days,
  // then 50% in the last 24 hours. Keep the oldest record inside the 16-day retention edge.
  const ratio = index / Math.max(1, recordCount - 1);
  let ageDays;
  if (ratio < 0.20) ageDays = 15.9 - (ratio / 0.20) * 7.9;
  else if (ratio < 0.50) ageDays = 8 - ((ratio - 0.20) / 0.30) * 7;
  else ageDays = 1 - ((ratio - 0.50) / 0.50);
  return Math.max(0, Math.round(ageDays * 86400000));
}
function record(globalIndex, partner) {
  const journey = Math.floor(globalIndex / 7);
  const stageIndex = globalIndex % 7;
  const collisionPair = Math.floor(journey / 2);
  const collision = collisionPair % 10 === 0;
  const applicationId = collision
      ? `SYNTHETIC-COLLISION-${runId}-${pad(collisionPair, 8)}`
      : identifier('APPLICATIONID', partner, journey);
  const ids = {
    applicationId,
    loanId: identifier('LOANID', partner, journey),
    correlationId: identifier('CORRELATIONID', partner, journey),
    partnerReferenceId: identifier('PARTNERREFERENCEID', partner, journey),
    externalTransactionId: identifier('EXTERNALTRANSACTIONID', partner, journey),
    callbackReferenceId: identifier('CALLBACKREFERENCEID', partner, journey),
    requestId: identifier('REQUESTID', partner, journey),
  };
  const stages = ['PARTNER_API_REQUEST', 'PARTNER_API_RESPONSE', 'ASYNC_REQUEST_SENT',
    'ASYNC_ACK_RECEIVED', 'CALLBACK_RECEIVED', 'CALLBACK_PROCESSED', 'CALLBACK_RESPONSE_SENT'];
  const eventTypes = ['outbound_api_request', 'outbound_api_response', 'outbound_api_request',
    'async_acknowledgement', 'callback_request', 'callback_processing_event', 'callback_response'];
  const timestamp = startedMillis - ageMillis(globalIndex);
  const body = JSON.stringify(Object.assign({
    record: stages[stageIndex],
    eventId: uuid(globalIndex + 1),
    timestamp: new Date(timestamp).toISOString(),
    direction: stageIndex < 4 ? 'OUTBOUND_TO_PARTNER' : 'INBOUND_FROM_PARTNER',
    status: 'SUCCESS',
    payloadStatus: 'NOT_REQUESTED',
    timelineStage: stages[stageIndex],
    fixtureClassification: 'SYNTHETIC_ONLY',
  }, ids));
  const attributes = [
    integerAttribute('schema.version', 2), attribute('partner.key', partner === 'alpha' ? 'partner-alpha-fixture' : 'partner-beta-fixture'),
    attribute('event.type', eventTypes[stageIndex]), attribute('event.domain', stageIndex < 2 ? 'API' : stageIndex < 4 ? 'ASYNC' : 'CALLBACK'),
    attribute('direction', stageIndex < 4 ? 'OUTBOUND_TO_PARTNER' : 'INBOUND_FROM_PARTNER'), attribute('outcome', 'SUCCESS'),
    attribute('severity', 'INFO'), attribute('event.id', uuid(globalIndex + 1)), attribute('interaction.id', uuid(journey + 1000000)),
    attribute('correlation.profile.id', 'SYNTHETIC_ASYNC'), attribute('timeline.stage', stages[stageIndex]),
    attribute('api.id', stageIndex < 4 ? 'SYNTHETIC_ASYNC' : 'CREDIT_DECISION_CALLBACK'), attribute('service.version', 'performance-seed-v1'),
    attribute('application.id', ids.applicationId), attribute('loan.id', ids.loanId),
    attribute('correlation.id', ids.correlationId), attribute('original.correlation.id', ids.correlationId),
    attribute('partner.reference.id', ids.partnerReferenceId), attribute('external.transaction.id', ids.externalTransactionId),
    attribute('callback.reference.id', ids.callbackReferenceId), attribute('request.id', ids.requestId),
  ];
  return {timeUnixNano: `${timestamp}000000`, observedTimeUnixNano: `${timestamp}000000`, severityText: 'INFO', body: {stringValue: body}, attributes};
}

export default function () {
  const batch = exec.scenario.iterationInTest;
  if (batch >= batches) return;
  const first = batch * batchSize;
  const records = {alpha: [], beta: []};
  let recordsInBatch = 0;
  for (let offset = 0; offset < batchSize && first + offset < recordCount; offset++) {
    const globalIndex = first + offset;
    const partner = Math.floor(globalIndex / 7) % 2 === 0 ? 'alpha' : 'beta';
    records[partner].push(record(globalIndex, partner));
    recordsInBatch++;
  }
  let accepted = true;
  for (const partner of ['alpha', 'beta']) {
    if (records[partner].length === 0) continue;
    const body = JSON.stringify({resourceLogs: [{resource: {attributes: [
      attribute('service.name', serviceName), attribute('service.version', 'performance-seed-v1'),
      attribute('market', 'synthetic'), attribute('deployment.environment', 'local'),
    ]}, scopeLogs: [{scope: {name: 'partner-observability-performance-seed', version: '1'}, logRecords: records[partner]}]}]});
    const user = partner === 'alpha' ? alphaUser : betaUser;
    const password = partner === 'alpha' ? alphaPassword : betaPassword;
    const response = http.post(endpoint, body, {headers: {
      'Content-Type': 'application/json',
      Authorization: `Basic ${encoding.b64encode(`${user}:${password}`)}`,
      'X-Partner-Route': partner === 'alpha' ? 'partner-alpha-fixture' : 'partner-beta-fixture',
    }, timeout: '10s'});
    accepted = check(response, {'seed batch accepted': (value) => value.status >= 200 && value.status < 300}) && accepted;
  }
  if (accepted) recordsSeeded.add(recordsInBatch);
}
