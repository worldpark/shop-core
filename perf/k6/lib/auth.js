/**
 * lib/auth.js
 *
 * 인증 헬퍼 모듈 — 로그인·회원가입·Bearer 헤더 생성·토큰 갱신.
 * 시나리오·시드 모두 이 모듈을 통해 인증을 처리한다.
 *
 * 모든 함수는 실패 시 throw해 런을 즉시 중단한다.
 * check()만 하고 넘어가면 "조용한 0 RPS"가 발생하므로 허용하지 않는다.
 */

import http from 'k6/http';
import { BASE_URL } from './config.js';

// ---------------------------------------------------------------
// VU-로컬 토큰 캐시 (모듈 스코프 — VU 간 공유 안 됨, 이터레이션 간 유지됨)
// ---------------------------------------------------------------
// k6는 VU별로 모듈 인스턴스를 가지므로 이 Map은 VU 전용 캐시다.
// key: buyer 식별자(이메일 또는 __VU 기반), value: { accessToken, refreshToken, issuedAt }
const tokenCache = {};

// ---------------------------------------------------------------
// Bearer 헤더 헬퍼
// ---------------------------------------------------------------

/**
 * Authorization: Bearer {token} 헤더 객체를 반환한다.
 * @param {string} token JWT access token
 * @returns {{ headers: { 'Content-Type': string, 'Authorization': string } }}
 */
export function authHeaders(token) {
  return {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
  };
}

/**
 * Content-Type: application/json 헤더만 (인증 불필요 공개 API용).
 * @returns {{ headers: { 'Content-Type': string } }}
 */
export function jsonHeaders() {
  return {
    headers: {
      'Content-Type': 'application/json',
    },
  };
}

// ---------------------------------------------------------------
// 로그인
// ---------------------------------------------------------------

/**
 * POST /api/v1/auth/login 로그인 후 accessToken을 반환한다.
 *
 * @param {string} email
 * @param {string} password
 * @returns {string} JWT accessToken
 * @throws {Error} 200 이외 응답 시 즉시 throw (런 중단)
 */
export function login(email, password) {
  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    jsonHeaders(),
  );

  if (res.status !== 200) {
    throw new Error(
      `[auth.login] 로그인 실패 — email=${email} status=${res.status} body=${res.body}`,
    );
  }

  const body = JSON.parse(res.body);
  if (!body.accessToken) {
    throw new Error(
      `[auth.login] accessToken 미존재 — email=${email} body=${res.body}`,
    );
  }

  return body.accessToken;
}

// ---------------------------------------------------------------
// 토큰 갱신 헬퍼
// ---------------------------------------------------------------

/**
 * VU-로컬 캐시를 사용해 유효한 accessToken을 반환한다.
 *
 * 토큰 나이가 TOKEN_REFRESH_AFTER_SEC(기본 1500=25분, access TTL=30분-버퍼)을 초과하면
 * POST /api/v1/auth/refresh {refreshToken}으로 새 accessToken을 획득·캐시한다.
 * 그 외에는 기존 캐시 토큰을 반환한다.
 *
 * smoke/load(짧은 런)는 기본값 1500초에 도달하지 못하므로 갱신이 절대 발화하지 않는다.
 * 갱신 기능 검증은 -e TOKEN_REFRESH_AFTER_SEC=5 등 짧은 값으로 강제 발화한다.
 *
 * @param {{ accessToken: string, refreshToken: string, issuedAt: number, email: string }} buyer
 *   seed.js setupSeed()가 반환한 buyer 객체. issuedAt은 setup() 시점 epoch(ms).
 * @returns {string} 유효한 JWT accessToken
 * @throws {Error} refresh 실패 시 즉시 throw (런 중단)
 */
export function getValidToken(buyer) {
  // 갱신 발화 임계(초). 기본 1500(25분) = access TTL 30분 - 5분 버퍼.
  const refreshAfterSec = parseInt(__ENV.TOKEN_REFRESH_AFTER_SEC || '1500', 10);

  // VU-로컬 캐시 키: buyer 이메일(시드에서 고유 보장)
  const cacheKey = buyer.email;

  // 캐시 초기화: 첫 호출 시 seed에서 받은 값으로 채운다.
  if (!tokenCache[cacheKey]) {
    tokenCache[cacheKey] = {
      accessToken: buyer.accessToken,
      refreshToken: buyer.refreshToken,
      issuedAt: buyer.issuedAt,
    };
  }

  const cached = tokenCache[cacheKey];
  const ageMs = Date.now() - cached.issuedAt;
  const ageSec = ageMs / 1000;

  // 갱신 임계 초과 → refresh 호출
  if (ageSec > refreshAfterSec) {
    const res = http.post(
      `${BASE_URL}/api/v1/auth/refresh`,
      JSON.stringify({ refreshToken: cached.refreshToken }),
      jsonHeaders(),
    );

    if (res.status !== 200) {
      throw new Error(
        `[auth.getValidToken] 토큰 갱신 실패 — email=${cacheKey} status=${res.status} body=${res.body}`,
      );
    }

    const body = JSON.parse(res.body);
    if (!body.accessToken) {
      throw new Error(
        `[auth.getValidToken] 갱신 응답에 accessToken 미존재 — email=${cacheKey} body=${res.body}`,
      );
    }

    // 캐시 갱신. refreshToken은 응답에 포함될 경우 교체, 없으면 기존 유지.
    cached.accessToken = body.accessToken;
    if (body.refreshToken) {
      cached.refreshToken = body.refreshToken;
    }
    cached.issuedAt = Date.now();
  }

  return cached.accessToken;
}

// ---------------------------------------------------------------
// 로그인 (TokenResponse 전체 반환 variant) — seed.js 내부용
// ---------------------------------------------------------------

/**
 * POST /api/v1/auth/login 로그인 후 TokenResponse 전체를 반환한다.
 * seed.js가 refreshToken·issuedAt을 buyer 객체에 저장하기 위해 사용한다.
 *
 * @param {string} email
 * @param {string} password
 * @returns {{ accessToken: string, refreshToken: string, issuedAt: number }}
 * @throws {Error} 200 이외 응답 시 즉시 throw (런 중단)
 */
export function loginFull(email, password) {
  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    jsonHeaders(),
  );

  if (res.status !== 200) {
    throw new Error(
      `[auth.loginFull] 로그인 실패 — email=${email} status=${res.status} body=${res.body}`,
    );
  }

  const body = JSON.parse(res.body);
  if (!body.accessToken) {
    throw new Error(
      `[auth.loginFull] accessToken 미존재 — email=${email} body=${res.body}`,
    );
  }

  return {
    accessToken: body.accessToken,
    refreshToken: body.refreshToken || null,
    issuedAt: Date.now(),
  };
}

// ---------------------------------------------------------------
// 회원가입
// ---------------------------------------------------------------

/**
 * POST /api/v1/members/signup 회원가입 후 memberId를 반환한다.
 *
 * @param {string} email
 * @param {string} password   (8자 이상)
 * @param {string} name
 * @returns {number} memberId
 * @throws {Error} 201 이외 응답 시 즉시 throw (런 중단)
 */
export function signup(email, password, name) {
  const res = http.post(
    `${BASE_URL}/api/v1/members/signup`,
    JSON.stringify({
      email,
      password,
      passwordConfirm: password,
      name,
    }),
    jsonHeaders(),
  );

  if (res.status !== 201) {
    throw new Error(
      `[auth.signup] 회원가입 실패 — email=${email} status=${res.status} body=${res.body}`,
    );
  }

  const body = JSON.parse(res.body);
  if (!body.memberId) {
    throw new Error(
      `[auth.signup] memberId 미존재 — email=${email} body=${res.body}`,
    );
  }

  return body.memberId;
}
