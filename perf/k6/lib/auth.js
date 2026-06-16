/**
 * lib/auth.js
 *
 * 인증 헬퍼 모듈 — 로그인·회원가입·Bearer 헤더 생성.
 * 시나리오·시드 모두 이 모듈을 통해 인증을 처리한다.
 *
 * 모든 함수는 실패 시 throw해 런을 즉시 중단한다.
 * check()만 하고 넘어가면 "조용한 0 RPS"가 발생하므로 허용하지 않는다.
 */

import http from 'k6/http';
import { BASE_URL } from './config.js';

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
