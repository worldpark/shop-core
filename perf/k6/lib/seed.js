/**
 * lib/seed.js
 *
 * setup() 전용 시드 모듈.
 *
 * 실행 순서:
 *   1. admin 로그인 → adminToken
 *   2. seller 계정 signup → sellerMemberId
 *   3. admin이 seller 승격 (PATCH /admin/members/{id}/role)
 *   4. seller 재로그인 → sellerToken (SELLER 권한 토큰 확보)
 *   5. 상품 등록 (DRAFT) → productId
 *   6. 상품 게시 (ON_SALE) — 구매 가능 불변식 충족
 *   7. variant 생성 (stock 대량, active:true) → variantId
 *   8. buyer N개 signup + login → [{token}...]
 *
 * 모든 단계는 기대 status code가 아니면 즉시 throw해 런을 중단한다.
 * check()만 하고 넘어가면 "조용한 0 RPS"가 발생하므로 허용하지 않는다.
 *
 * 런별 유니크 prefix(네임스페이스):
 *   __ENV.RUN_TAG 우선, 없으면 uuidv4() 자동 생성.
 *   Date.now() / Math.random() 은 이 프로젝트 규약상 사용 금지.
 */

import http from 'k6/http';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

import {
  ADMIN_EMAIL,
  ADMIN_PASSWORD,
  SEED,
} from './config.js';
import { login, loginFull, signup, authHeaders, jsonHeaders } from './auth.js';

// ---------------------------------------------------------------
// 내부 헬퍼 — admin 권한으로 seller 승격
// ---------------------------------------------------------------

/**
 * PATCH /api/v1/admin/members/{memberId}/role {role:"SELLER"}
 * @param {string} adminToken
 * @param {number} memberId
 * @throws {Error} 200 이외 즉시 throw
 */
function promoteToSeller(adminToken, memberId) {
  const res = http.patch(
    `${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1/admin/members/${memberId}/role`,
    JSON.stringify({ role: 'SELLER' }),
    authHeaders(adminToken),
  );

  if (res.status !== 200) {
    throw new Error(
      `[seed.promoteToSeller] 승격 실패 — memberId=${memberId} status=${res.status} body=${res.body}`,
    );
  }
}

// ---------------------------------------------------------------
// 내부 헬퍼 — 상품 등록
// ---------------------------------------------------------------

/**
 * POST /api/v1/seller/products → productId (status=DRAFT)
 * @param {string} sellerToken
 * @param {string} prefix 런별 유니크 prefix
 * @returns {number} productId
 * @throws {Error} 200 이외 즉시 throw
 */
function registerProduct(sellerToken, prefix) {
  const res = http.post(
    `${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1/seller/products`,
    JSON.stringify({
      categoryId: null,
      name: `${SEED.PRODUCT_NAME_PREFIX}-${prefix}`,
      description: `k6 perf test product [${prefix}]`,
      basePrice: SEED.BASE_PRICE,
    }),
    authHeaders(sellerToken),
  );

  if (res.status !== 200) {
    throw new Error(
      `[seed.registerProduct] 상품 등록 실패 — status=${res.status} body=${res.body}`,
    );
  }

  const body = JSON.parse(res.body);
  if (!body.productId) {
    throw new Error(
      `[seed.registerProduct] productId 미존재 — body=${res.body}`,
    );
  }

  return body.productId;
}

// ---------------------------------------------------------------
// 내부 헬퍼 — 상품 게시 (ON_SALE)
// ---------------------------------------------------------------

/**
 * PATCH /api/v1/seller/products/{productId} {status:"ON_SALE"}
 * 구매 가능 불변식: product.status==ON_SALE && variant.active 필수.
 * @param {string} sellerToken
 * @param {number} productId
 * @param {string} prefix
 * @throws {Error} 200 이외 즉시 throw
 */
function publishProduct(sellerToken, productId, prefix) {
  const res = http.patch(
    `${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1/seller/products/${productId}`,
    JSON.stringify({
      categoryId: null,
      name: `${SEED.PRODUCT_NAME_PREFIX}-${prefix}`,
      description: `k6 perf test product [${prefix}]`,
      basePrice: SEED.BASE_PRICE,
      status: 'ON_SALE',
    }),
    authHeaders(sellerToken),
  );

  if (res.status !== 200) {
    throw new Error(
      `[seed.publishProduct] 상품 게시 실패 — productId=${productId} status=${res.status} body=${res.body}`,
    );
  }
}

// ---------------------------------------------------------------
// 내부 헬퍼 — variant 생성
// ---------------------------------------------------------------

/**
 * POST /api/v1/seller/products/{productId}/variants → variantId
 * @param {string} sellerToken
 * @param {number} productId
 * @param {string} prefix
 * @returns {number} variantId
 * @throws {Error} 200 이외 즉시 throw
 */
function createVariant(sellerToken, productId, prefix) {
  const res = http.post(
    `${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1/seller/products/${productId}/variants`,
    JSON.stringify({
      sku: `${SEED.SKU_PREFIX}-${prefix}`,
      price: SEED.VARIANT_PRICE,
      stock: SEED.VARIANT_STOCK,
      active: true,
      optionValueIds: [],
    }),
    authHeaders(sellerToken),
  );

  if (res.status !== 200) {
    throw new Error(
      `[seed.createVariant] variant 생성 실패 — productId=${productId} status=${res.status} body=${res.body}`,
    );
  }

  const body = JSON.parse(res.body);
  if (!body.variantId) {
    throw new Error(
      `[seed.createVariant] variantId 미존재 — body=${res.body}`,
    );
  }

  return body.variantId;
}

// ---------------------------------------------------------------
// 공개 API — setupSeed()
// ---------------------------------------------------------------

/**
 * k6 setup() 에서 호출하는 전체 시드 흐름.
 *
 * @param {number} buyerCount 생성할 buyer 수 (= 프로파일 최대 VU)
 * @returns {{ variantId: number, buyers: Array<{ token: string }> }}
 * @throws {Error} 어느 단계든 실패 시 즉시 throw → 런 중단
 */
export function setupSeed(buyerCount) {
  // 런별 유니크 prefix: RUN_TAG 우선, 없으면 uuidv4()
  const prefix = __ENV.RUN_TAG || uuidv4();

  const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';

  // 1. admin 로그인
  const adminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);

  // 2. seller 계정 signup
  const sellerEmail = `seller+${prefix}${SEED.SELLER_EMAIL_DOMAIN}`;
  const sellerMemberId = signup(sellerEmail, SEED.DEFAULT_PASSWORD, `Perf-Seller-${prefix}`);

  // 3. admin이 seller 승격
  promoteToSeller(adminToken, sellerMemberId);

  // 4. seller 재로그인 (승격 후 SELLER 권한 토큰 확보)
  const sellerToken = login(sellerEmail, SEED.DEFAULT_PASSWORD);

  // 5. 상품 등록 (DRAFT)
  const productId = registerProduct(sellerToken, prefix);

  // 6. 상품 게시 (ON_SALE) — 구매 가능 불변식 충족
  publishProduct(sellerToken, productId, prefix);

  // 7. variant 생성 (stock 대량, active:true)
  const variantId = createVariant(sellerToken, productId, prefix);

  // 8. buyer N개 signup + login
  // buyer 객체: { token, accessToken, refreshToken, issuedAt, email }
  //   - token: 하위 호환(기존 코드가 buyer.token을 직접 참조하는 경우)
  //   - accessToken/refreshToken/issuedAt: getValidToken() 토큰 갱신용
  //   - email: getValidToken() VU-로컬 캐시 키
  const buyers = [];
  for (let i = 0; i < buyerCount; i++) {
    const buyerEmail = `buyer+${prefix}+${i}${SEED.BUYER_EMAIL_DOMAIN}`;
    const buyerName  = `Perf-Buyer-${prefix}-${i}`;

    signup(buyerEmail, SEED.DEFAULT_PASSWORD, buyerName);
    const { accessToken, refreshToken, issuedAt } = loginFull(buyerEmail, SEED.DEFAULT_PASSWORD);

    buyers.push({
      token: accessToken,         // 하위 호환 (기존 buyer.token 참조)
      accessToken,                // getValidToken() 캐시 초기값
      refreshToken,               // getValidToken() refresh 호출용
      issuedAt,                   // 토큰 발급 시각(ms) — 나이 계산용
      email: buyerEmail,          // getValidToken() VU-로컬 캐시 키
    });
  }

  return { variantId, buyers };
}
