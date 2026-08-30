import exec from 'k6/execution';
import encoding from 'k6/encoding';
import {Counter, Trend} from 'k6/metrics';
import {check, sleep} from 'k6';
import {
  appBaseUrl, reactiveBaseUrl, queryBaseUrl, profileId, executionId, runId,
  partner, post, checkedJson, chooseByPercent, fixturePayload, syntheticId,
} from './common.js';

const durationSeconds = Number(__ENV.PERF_DURATION_SECONDS || '10');
const arrivalRate = Number(__ENV.PERF_ARRIVAL_RATE || '0');
const concurrency = Number(__ENV.PERF_CONCURRENCY || '0');
const phase = __ENV.PERF_PHASE || 'measured';
const summaryPath = __ENV.K6_SUMMARY_PATH || 'k6-summary.json';
const measuredStartsAfterSeconds = Number(__ENV.PERF_MEASURE_START_AFTER_SECONDS || '0');
const measuredWindowSeconds = Number(__ENV.PERF_MEASURE_DURATION_SECONDS || durationSeconds);
const performanceMode = __ENV.PERF_MODE || 'full';

export const scheduledOperations = new Counter('scheduled_operations');
export const completedOperations = new Counter('completed_operations');
export const successfulOperations = new Counter('successful_operations');
export const businessErrors = new Counter('business_errors_attributable_to_observability');
export const callbackSuccesses = new Counter('callback_successes');
export const callbackFailures = new Counter('callback_failures_attributable_to_observability');
export const intentionalBusinessFailures = new Counter('intentional_business_failures');
export const intentionalCallbackProcessingFailures = new Counter('intentional_callback_processing_failures');
export const expectedCancellations = new Counter('expected_cancellations');
export const businessLatency = new Trend('business_latency', true);
export const callbackLatency = new Trend('callback_latency', true);
export const journeyLatency = new Trend('journey_latency', true);
export const mixedSyncSuccess = new Counter('mixed_sync_success');
export const mixedAsyncJourney = new Counter('mixed_async_journey');
export const mixedPartner4xx = new Counter('mixed_partner_4xx');
export const mixedPartner5xx = new Counter('mixed_partner_5xx');
export const mixedTimeout = new Counter('mixed_timeout');
export const mixedNetworkFailure = new Counter('mixed_network_failure');
export const mixedLargeJson = new Counter('mixed_large_json');
export const mixedBinaryDocument = new Counter('mixed_binary_document');
export const journeyLimitViolations = new Counter('journey_limit_violations');
export const journeyTenantViolations = new Counter('journey_tenant_violations');
export const journeyResponseBytes = new Trend('journey_response_bytes');
export const callbackInline = new Counter('callback_inline');
export const callbackShortDeferred = new Counter('callback_short_deferred');
export const callbackLongDeferred = new Counter('callback_long_deferred');
export const statusChanged = new Counter('status_changed');
export const exceptionChanged = new Counter('exception_changed');
export const bodyChanged = new Counter('body_changed');
export const reactiveElements = new Counter('reactive_elements');
export const reactiveIncompleteResponses = new Counter('reactive_incomplete_responses');
export const mixedPdfDocuments = new Counter('mixed_pdf_documents');
export const mixedImages = new Counter('mixed_images');
export const mixedUnknownBinary = new Counter('mixed_unknown_binary');
export const mixedMalformed = new Counter('mixed_malformed');
export const asyncDuplicateCallbacks = new Counter('async_duplicate_callbacks');
export const asyncCallbackRetries = new Counter('async_callback_retries');
export const asyncMultipleCallbacks = new Counter('async_multiple_callbacks');
export const asyncCallbackPdf = new Counter('async_callback_pdf');
export const asyncCallbackImage = new Counter('async_callback_image');
export const asyncCallbackProcessingFailure = new Counter('async_callback_processing_failure');
export const journeyEmptyResults = new Counter('journey_empty_results');
export const journeyQueries = new Counter('journey_queries');
export const journeyQueryFailures = new Counter('journey_query_failures');

function executorOptions() {
  if (phase === 'combined' && (profileId === 'reactive' || profileId === 'callback-webflux')) {
    const steadySeconds = Math.max(1, durationSeconds - 10);
    return {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [{duration: '10s', target: concurrency}, {duration: `${steadySeconds}s`, target: concurrency}],
      gracefulRampDown: '0s',
      gracefulStop: '5s',
    };
  }
  if (arrivalRate > 0) {
    return {
      executor: 'constant-arrival-rate',
      rate: arrivalRate,
      timeUnit: '1s',
      duration: `${durationSeconds}s`,
      preAllocatedVUs: Math.max(50, Math.min(arrivalRate, 2000)),
      maxVUs: Math.max(500, arrivalRate * 2),
      gracefulStop: '5s',
    };
  }
  return {
    executor: 'constant-vus',
    vus: Math.max(1, concurrency),
    duration: `${durationSeconds}s`,
    gracefulStop: '5s',
  };
}

const profileThresholds = {
  business_errors_attributable_to_observability: ['count==0'],
  callback_failures_attributable_to_observability: ['count==0'],
};
// Warm-up is deliberately not acceptance evidence. Requiring every tagged query bucket during
// a short warm-up makes an otherwise healthy run exit before the measured phase can start.
if (profileId === 'journey-query' && phase !== 'warmup') Object.assign(profileThresholds, {
    'journey_queries{queryType:applicationId}': ['count>0'],
    'journey_queries{queryType:loanId}': ['count>0'],
    'journey_queries{queryType:correlationId}': ['count>0'],
    'journey_queries{queryType:partnerReferenceId}': ['count>0'],
    'journey_queries{queryType:callbackReferenceId}': ['count>0'],
    'journey_queries{queryType:journey}': ['count>0'],
    'journey_queries{queryType:detail}': ['count>0'],
    'journey_queries{ageBucket:recent}': ['count>0'],
    'journey_queries{ageBucket:middle}': ['count>0'],
    'journey_queries{ageBucket:old}': ['count>0'],
    'journey_queries{collision:true}': ['count>0'],
    'journey_query_failures{queryType:applicationId}': ['count==0'],
    'journey_query_failures{queryType:loanId}': ['count==0'],
    'journey_query_failures{queryType:correlationId}': ['count==0'],
    'journey_query_failures{queryType:partnerReferenceId}': ['count==0'],
    'journey_query_failures{queryType:callbackReferenceId}': ['count==0'],
    'journey_query_failures{queryType:journey}': ['count==0'],
    'journey_query_failures{queryType:detail}': ['count==0'],
});

export const options = {
  discardResponseBodies: false,
  scenarios: {profile: executorOptions()},
  thresholds: profileThresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
};

function iteration() {
  // constant-vus/ramping-vus expose a per-VU iteration sequence. Stripe that sequence across the
  // configured concurrency so percentage buckets remain global instead of every VU beginning in
  // bucket zero. constant-arrival-rate already provides the scenario-wide sequence needed here.
  if (arrivalRate === 0 && concurrency > 0) {
    return exec.vu.iterationInScenario * concurrency + (__VU - 1);
  }
  return exec.scenario.iterationInTest;
}

function inMeasuredWindow() {
  if (phase === 'measured') return true;
  if (phase !== 'combined') return false;
  const elapsedSeconds = (Date.now() - Number(exec.scenario.startTime)) / 1000;
  return elapsedSeconds >= measuredStartsAfterSeconds &&
      elapsedSeconds < measuredStartsAfterSeconds + measuredWindowSeconds;
}

function add(metricName, value, tags) {
  if (inMeasuredWindow()) metricName.add(value, tags);
}

function recordBusiness(response, label) {
  add(businessLatency, response.timings.duration, {scenario: label});
  add(completedOperations, 1, {scenario: label});
  const value = checkedJson(response, label);
  if (value === null) {
    add(businessErrors, 1, {scenario: label});
    return null;
  }
  add(successfulOperations, 1, {scenario: label});
  verifyOutboundContract(value, label);
  return value;
}

function verifyOutboundContract(value, label) {
  const scenario = String(value.scenario || '');
  const expectedStatus = scenario === 'PARTNER_4XX' ? 422 : scenario === 'PARTNER_5XX' ? 503 :
      (scenario === 'TIMEOUT' || scenario === 'CONNECTION_FAILURE') ? 0 : 200;
  if (Number(value.httpStatus) !== expectedStatus) add(statusChanged, 1, {scenario: label});
  const expectsFailure = expectedStatus === 0;
  if (expectsFailure !== Boolean(value.failureType)) add(exceptionChanged, 1, {scenario: label});
  if (!expectsFailure && (!value.responseSha256 || Number(value.responseBytes) <= 0)) {
    add(bodyChanged, 1, {scenario: label});
  }
}

function outboundScenario(client, scenario, iterationNumber) {
  const lane = partner(iterationNumber);
  return recordBusiness(
      post(`${appBaseUrl}/fixture/${client}/${lane}/${scenario}`, null, {kind: 'outbound', client, scenario}),
      `${client}-${scenario}`);
}

function outbound() {
  const current = iteration();
  const client = chooseByPercent(current, [
    {percent: 50, value: 'rest'}, {percent: 25, value: 'webclient'}, {percent: 25, value: 'okhttp'},
  ]);
  outboundScenario(client, profileId === 'full-sanitized' ? 'large-normal-json' : 'success', current);
}

function startCallback(scenario, iterationNumber) {
  const lane = partner(iterationNumber);
  const started = Date.now();
  const response = post(`${appBaseUrl}/fixture/async/${lane}/${scenario}`, null, {kind: 'callback', scenario}, '10s');
  add(callbackLatency, Date.now() - started, {scenario});
  add(completedOperations, 1, {scenario});
  const body = checkedJson(response, `callback-${scenario}`);
  if (body === null || !body.runId) {
    add(callbackFailures, 1, {scenario});
    return null;
  }
  add(callbackSuccesses, 1, {scenario});
  add(successfulOperations, 1, {scenario});
  if (Number(body.acknowledgementHttpStatus) !== 202 || body.acknowledgementReceived !== true) {
    add(statusChanged, 1, {scenario});
  }
  return body;
}

function callbackMvc() {
  const current = iteration();
  const intentionalFailure = current % 20 === 0;
  // The approved 90/8/2 completion mix applies to the normal callback population. Keep the
  // separate five-percent intentional business-failure workload out of that denominator.
  const normalOrdinal = current - Math.floor(current / 20) - 1;
  let scenario = chooseByPercent(Math.max(0, normalOrdinal), [
    {percent: 90, value: 'performance-inline-success'},
    {percent: 8, value: 'performance-short-deferred-success'},
    {percent: 2, value: 'performance-long-deferred-success'},
  ]);
  if (intentionalFailure) {
    scenario = 'callback-processing-failure';
    add(intentionalBusinessFailures, 1, {scenario});
    add(intentionalCallbackProcessingFailures, 1, {scenario});
  } else {
    if (scenario === 'performance-inline-success') add(callbackInline, 1);
    if (scenario === 'performance-short-deferred-success') add(callbackShortDeferred, 1);
    if (scenario === 'performance-long-deferred-success') add(callbackLongDeferred, 1);
  }
  startCallback(scenario, current);
}

function mixed() {
  const current = iteration();
  const bucket = current % 100;
  if (bucket < 65) {
    add(mixedSyncSuccess, 1);
    outboundScenario(bucket % 2 === 0 ? 'rest' : 'webclient', 'success', current);
  } else if (bucket < 80) {
    add(mixedAsyncJourney, 1);
    const asyncCases = ['callback-success', 'duplicate-callback', 'callback-retry', 'multiple-callbacks',
      'callback-pdf-base64-5-mb', 'callback-image-base64', 'callback-processing-failure'];
    const selected = asyncCases[current % asyncCases.length];
    if (selected === 'duplicate-callback') add(asyncDuplicateCallbacks, 1);
    if (selected === 'callback-retry') add(asyncCallbackRetries, 1);
    if (selected === 'multiple-callbacks') add(asyncMultipleCallbacks, 1);
    if (selected === 'callback-pdf-base64-5-mb') add(asyncCallbackPdf, 1);
    if (selected === 'callback-image-base64') add(asyncCallbackImage, 1);
    if (selected === 'callback-processing-failure') add(asyncCallbackProcessingFailure, 1);
    startCallback(selected, current);
    if (selected === 'callback-processing-failure' || selected === 'callback-retry') {
      add(intentionalCallbackProcessingFailures, 1, {scenario: selected});
    }
    if (selected === 'callback-processing-failure') add(intentionalBusinessFailures, 1, {scenario: selected});
  } else if (bucket < 84) {
    add(mixedPartner4xx, 1);
    outboundScenario('rest', 'partner-4xx', current);
    add(intentionalBusinessFailures, 1, {scenario: 'partner-4xx'});
  } else if (bucket < 87) {
    add(mixedPartner5xx, 1);
    outboundScenario('rest', 'partner-5xx', current);
    add(intentionalBusinessFailures, 1, {scenario: 'partner-5xx'});
  } else if (bucket < 89) {
    add(mixedTimeout, 1);
    outboundScenario('rest', 'timeout', current);
    add(intentionalBusinessFailures, 1, {scenario: 'timeout'});
  } else if (bucket < 90) {
    add(mixedNetworkFailure, 1);
    outboundScenario('rest', 'connection-failure', current);
    add(intentionalBusinessFailures, 1, {scenario: 'connection-failure'});
  } else if (bucket < 95) {
    add(mixedLargeJson, 1);
    outboundScenario('rest', 'mixed-large-json-96-kib', current);
  } else {
    add(mixedBinaryDocument, 1);
    const binaryCases = ['pdf-request-base64-5-mb', 'jpeg-request-base64-8-mb',
      'unknown-request-large-base64', 'malformed-response-binary-request'];
    const binaryCase = binaryCases[current % binaryCases.length];
    if (binaryCase === 'pdf-request-base64-5-mb') add(mixedPdfDocuments, 1);
    if (binaryCase === 'jpeg-request-base64-8-mb') add(mixedImages, 1);
    if (binaryCase === 'unknown-request-large-base64') add(mixedUnknownBinary, 1);
    if (binaryCase === 'malformed-response-binary-request') add(mixedMalformed, 1);
    outboundScenario('rest', binaryCase, current);
  }
}

function resilience() {
  const current = iteration();
  if (current % 167 === 0) {
    startCallback('callback-success', current);
  } else {
    outboundScenario(current % 2 === 0 ? 'rest' : 'webclient', 'success', current);
  }
}

function reactive() {
  const current = iteration();
  // Exercise the real auto-configured WebClient exchange before the cancellable Reactor stream.
  // The matched disabled run executes the identical workload shape.
  outboundScenario('webclient', 'success', current);
  const cancelled = current % 4 === 0;
  // Deterministic pseudo-random permutation gives a reproducible, uniform 5-15 second window.
  // Smoke shortens only its fixture timing so it still exercises cancellation mechanics; full
  // mode always retains the approved 5-15 second cancellation window.
  const timeout = cancelled
      ? (performanceMode === 'smoke'
          ? `${200 + ((current * 73 + 19) % 401)}ms`
          : `${5 + ((current * 73 + 19) % 11)}s`)
      : '25s';
  const started = Date.now();
  const response = post(
      `${reactiveBaseUrl}/fixture/reactive/stream/${partner(current)}`,
      fixturePayload(2048, current), {kind: 'reactive-stream', cancelled: String(cancelled)}, timeout);
  add(businessLatency, Date.now() - started, {scenario: 'reactive-stream'});
  add(completedOperations, 1, {scenario: 'reactive-stream'});
  if (cancelled && response.status === 0) {
    add(expectedCancellations, 1);
    add(successfulOperations, 1, {scenario: 'reactive-stream-cancelled'});
    return;
  }
  const elements = response.body.trim() ? response.body.trim().split('\n').length : 0;
  add(reactiveElements, elements);
  if (!check(response, {'reactive-stream: completed 32-element NDJSON response': (value) => value.status === 200 && elements === 32})) {
    add(reactiveIncompleteResponses, 1);
    add(businessErrors, 1, {scenario: 'reactive-stream'});
  } else {
    add(successfulOperations, 1, {scenario: 'reactive-stream'});
  }
}

function callbackWebflux() {
  const current = iteration();
  const expectedCorrelationId = syntheticId('CORRELATION', current);
  const cancelled = current % 5 === 0;
  const completedOrdinal = current - Math.floor(current / 5);
  const completion = cancelled ? 'cancel' : chooseByPercent(completedOrdinal, [
    {percent: 90, value: 'inline'}, {percent: 8, value: 'short'}, {percent: 2, value: 'long'},
  ]);
  if (completion === 'inline') add(callbackInline, 1);
  if (completion === 'short') add(callbackShortDeferred, 1);
  if (completion === 'long') add(callbackLongDeferred, 1);
  const started = Date.now();
  const response = post(
      `${reactiveBaseUrl}/fixture/reactive/callback/${partner(current)}?completion=${completion}`,
      fixturePayload(4096, current), {kind: 'callback-webflux', cancelled: String(cancelled)},
      cancelled ? `${5 + ((current * 73 + 19) % 11)}s` : '5s');
  add(callbackLatency, Date.now() - started, {scenario: 'callback-webflux'});
  add(completedOperations, 1, {scenario: 'callback-webflux'});
  if (cancelled && response.status === 0) {
    add(expectedCancellations, 1);
    add(callbackSuccesses, 1, {scenario: 'cancelled'});
    add(successfulOperations, 1, {scenario: 'callback-webflux-cancelled'});
    return;
  }
  const expectedStatus = completion === 'short' || completion === 'long' ? 202 : 200;
  if (!check(response, {'callback-webflux: response preserves completion contract':
      (value) => value.status === expectedStatus && value.body.length > 0 &&
          value.body.includes(expectedCorrelationId)})) {
    add(callbackFailures, 1, {scenario: 'callback-webflux'});
  } else {
    add(callbackSuccesses, 1, {scenario: 'callback-webflux'});
    add(successfulOperations, 1, {scenario: 'callback-webflux'});
  }
}

function journeyQuery() {
  const current = iteration();
  const queryType = chooseByPercent(current, [
    {percent: 30, value: 'applicationId'}, {percent: 20, value: 'loanId'},
    {percent: 15, value: 'correlationId'}, {percent: 10, value: 'partnerReferenceId'},
    {percent: 10, value: 'callbackReferenceId'}, {percent: 10, value: 'journey'},
    {percent: 5, value: 'detail'},
  ]);
  const lane = partner(current);
  const seedRecordCount = Number(__ENV.SEED_RECORD_COUNT || '500000');
  const journeyCount = Math.floor(seedRecordCount / 7);
  const ageBucket = current % 10 < 5 ? 'recent' : current % 10 < 8 ? 'middle' : 'old';
  const inAgeBucket = (candidate) => {
    const ratio = (candidate * 7) / Math.max(1, seedRecordCount - 1);
    if (ageBucket === 'recent') return ratio >= 0.50;
    if (ageBucket === 'middle') return ratio >= 0.20 && ratio < 0.50;
    return ratio < 0.20;
  };
  const bucketStartRatio = ageBucket === 'old' ? 0 : ageBucket === 'middle' ? 0.20 : 0.50;
  const bucketEndRatio = ageBucket === 'old' ? 0.20 : ageBucket === 'middle' ? 0.50 : 1;
  const firstJourney = Math.ceil((bucketStartRatio * seedRecordCount) / 7);
  const lastJourney = Math.min(journeyCount - 1, Math.floor((bucketEndRatio * seedRecordCount - 1) / 7));
  let firstJourneyForLane = firstJourney;
  if ((firstJourneyForLane % 2 === 0) !== (lane === 'alpha')) firstJourneyForLane++;
  const advanceInBucket = (candidate) => candidate + 2 > lastJourney ? firstJourneyForLane : candidate + 2;
  let journey = (current * 2 + (lane === 'alpha' ? 0 : 1)) % journeyCount;
  let seed;
  // Exactly 10% of the 100-iteration query mix uses an application identifier shared by both
  // tenants. Collision identifiers are application IDs, so never submit one as another type.
  const collisionQuery = (queryType === 'applicationId' || queryType === 'journey') && current % 4 === 0;
  if (collisionQuery) {
    const firstCollisionPair = Math.ceil((firstJourney / 2) / 10) * 10;
    const lastCollisionPair = Math.floor((lastJourney / 2) / 10) * 10;
    const collisionPairCount = Math.max(1, Math.floor((lastCollisionPair - firstCollisionPair) / 10) + 1);
    const collisionPair = firstCollisionPair + (Math.floor(current / 10) % collisionPairCount) * 10;
    journey = collisionPair * 2 + (lane === 'alpha' ? 0 : 1);
    seed = `SYNTHETIC-COLLISION-${runId}-${String(collisionPair).padStart(8, '0')}`;
  } else {
    for (let attempt = 0; attempt < journeyCount && !inAgeBucket(journey); attempt++) {
      journey = advanceInBucket(journey);
    }
    if (queryType === 'applicationId' || queryType === 'journey') {
      for (let attempt = 0; attempt < journeyCount && Math.floor(journey / 2) % 10 === 0; attempt++) {
        journey = advanceInBucket(journey);
      }
    }
    const prefixes = {
      applicationId: 'APPLICATIONID', loanId: 'LOANID', correlationId: 'CORRELATIONID',
      partnerReferenceId: 'PARTNERREFERENCEID', callbackReferenceId: 'CALLBACKREFERENCEID',
      journey: 'APPLICATIONID', detail: 'REQUESTID',
    };
    seed = `SYNTHETIC-${prefixes[queryType]}-${runId}-${lane}-${String(journey).padStart(8, '0')}`;
  }
  const request = JSON.stringify({type: queryType, value: seed, fromDaysAgo: ageBucket === 'recent' ? 1 : ageBucket === 'middle' ? 8 : 16});
  add(journeyQueries, 1, {queryType, ageBucket, collision: String(collisionQuery)});
  const started = Date.now();
  const username = lane === 'alpha' ? (__ENV.PERF_QUERY_ALPHA_USERNAME || 'query-partner-a')
      : (__ENV.PERF_QUERY_BETA_USERNAME || 'query-partner-b');
  const password = lane === 'alpha' ? (__ENV.PERF_QUERY_ALPHA_PASSWORD || 'local-synthetic-query-a')
      : (__ENV.PERF_QUERY_BETA_PASSWORD || 'local-synthetic-query-b');
  const response = post(`${queryBaseUrl}/fixture/journey`, request, {kind: 'journey-query', queryType}, '10s',
      {Authorization: `Basic ${encoding.b64encode(`${username}:${password}`)}`});
  add(journeyLatency, Date.now() - started, {queryType});
  add(completedOperations, 1, {scenario: 'journey-query'});
  const body = checkedJson(response, 'journey-query');
  if (body !== null) add(journeyResponseBytes, body.projectedBytes || 0, {queryType});
  if (body !== null && body.partner !== lane) add(journeyTenantViolations, 1);
  if (body !== null && Number(body.records || 0) === 0) add(journeyEmptyResults, 1);
  if (body !== null && (body.rounds > 3 || body.identifiers > 32 || body.records > 1500 || body.projectedBytes > 2097152)) {
    add(journeyLimitViolations, 1);
  }
  if (body === null || body.partner !== lane || body.records < 1 || body.rounds > 3 || body.identifiers > 32 || body.records > 1500 || body.projectedBytes > 2097152) {
    add(businessErrors, 1, {scenario: 'journey-query'});
    add(journeyQueryFailures, 1, {queryType, ageBucket, lane, collision: String(collisionQuery)});
  } else {
    add(successfulOperations, 1, {scenario: 'journey-query'});
  }
  sleep(0.75);
}

export default function () {
  if (phase === 'cooldown') {
    sleep(1);
    return;
  }
  add(scheduledOperations, 1, {scenario: profileId});
  switch (profileId) {
    case 'disabled':
    case 'metadata':
    case 'full-sanitized': outbound(); break;
    case 'saturation': resilience(); break;
    case 'mixed-soak': mixed(); break;
    case 'reactive': reactive(); break;
    case 'callback-mvc': callbackMvc(); break;
    case 'callback-webflux': callbackWebflux(); break;
    case 'journey-query': journeyQuery(); break;
    default: throw new Error(`Unsupported performance profile: ${profileId}`);
  }
}

export function handleSummary(data) {
  const sanitized = {
    profileId, executionId, phase, durationSeconds,
    state: data.state,
    metrics: data.metrics,
    rootGroup: data.root_group,
  };
  return {[summaryPath]: JSON.stringify(sanitized, null, 2)};
}
