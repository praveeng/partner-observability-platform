import http from 'k6/http';
import { check } from 'k6';

export const appBaseUrl = __ENV.TEST_APP_BASE_URL || 'http://127.0.0.1:18080';
export const reactiveBaseUrl = __ENV.REACTIVE_TEST_APP_BASE_URL || 'http://127.0.0.1:18081';
export const queryBaseUrl = __ENV.QUERY_BASE_URL || appBaseUrl;
export const runId = __ENV.RUN_ID || 'UNSET-RUN-ID';
export const profileId = __ENV.PERF_PROFILE || 'unset';
export const executionId = __ENV.PERF_EXECUTION || 'default';
const reactiveCallbackKey = __ENV.LOCAL_SYNTHETIC_CALLBACK_KEY || 'local-synthetic-reactive-callback-key';

export function partner(iteration) {
  return iteration % 2 === 0 ? 'alpha' : 'beta';
}

export function syntheticId(kind, iteration) {
  const vu = String(__VU).padStart(4, '0');
  const item = String(iteration).padStart(12, '0');
  return `SYNTHETIC-${kind}-${runId}-${vu}-${item}`.replace(/[^A-Za-z0-9._-]/g, '-').slice(0, 128);
}

export function post(path, body, tags, timeout, additionalHeaders) {
  const params = {
    headers: Object.assign({
      'Content-Type': 'application/json',
      'X-Performance-Run-Id': runId,
      'X-Synthetic-Callback-Key': reactiveCallbackKey,
    }, additionalHeaders || {}),
    tags: Object.assign({profile: profileId, execution: executionId}, tags || {}),
    timeout: timeout || '10s',
    responseCallback: http.expectedStatuses({min: 200, max: 299}),
  };
  return http.post(path, body || null, params);
}

export function checkedJson(response, label) {
  const ok = check(response, {
    [`${label}: HTTP success`]: (value) => value.status >= 200 && value.status < 300,
    [`${label}: bounded JSON response`]: (value) => value.body.length <= 2 * 1024 * 1024,
  });
  if (!ok) return null;
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

export function chooseByPercent(iteration, entries) {
  const selected = iteration % 100;
  let upper = 0;
  for (const entry of entries) {
    upper += entry.percent;
    if (selected < upper) return entry.value;
  }
  return entries[entries.length - 1].value;
}

export function fixturePayload(bytes, iteration) {
  const marker = syntheticId('PAYLOAD', iteration);
  const value = {
    fixtureClassification: 'SYNTHETIC_ONLY',
    marker,
    applicationId: syntheticId('APPLICATION', iteration),
    correlationId: syntheticId('CORRELATION', iteration),
    callbackReferenceId: syntheticId('CALLBACK', iteration),
    requestedBytes: bytes,
    padding: '',
  };
  const encoded = JSON.stringify(value);
  value.padding = 'X'.repeat(Math.max(0, bytes - encoded.length));
  return JSON.stringify(value);
}
