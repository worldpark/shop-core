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

// ---------------------------------------------------------------
// 공개 API — setupCouponSeed() (coupon-apply.js 전용)
// ---------------------------------------------------------------

/**
 * coupon-apply 시나리오 전용 시드 흐름.
 *
 * setupSeed()를 호출해 seller/상품/variant/buyer를 구성한 뒤,
 * admin이 무제한 쿠폰 1개를 생성하고, 각 buyer가 그 쿠폰을 1회 발급받아
 * userCouponId를 buyer 객체에 추가한다.
 *
 * ★ 기존 setupSeed() 반환값은 변경하지 않는다.
 *   쿠폰 생성/발급은 이 함수에서만 호출되므로 order-create·payment-confirm 시드에 무영향.
 *
 * @param {number} buyerCount 생성할 buyer 수 (= 프로파일 최대 VU)
 * @returns {{
 *   variantId: number,
 *   buyers: Array<{
 *     token: string,
 *     accessToken: string,
 *     refreshToken: string,
 *     issuedAt: number,
 *     email: string,
 *     userCouponId: number
 *   }>
 * }}
 * @throws {Error} 어느 단계든 실패 시 즉시 throw → 런 중단
 */
export function setupCouponSeed(buyerCount) {
  // 1~8단계: 기존 setupSeed 재사용 (seller/상품/variant/buyer 구성)
  const seed = setupSeed(buyerCount);
  const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';

  // 9. admin 로그인 (쿠폰 생성 권한)
  const adminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);

  // 10. 무제한 쿠폰 1개 생성 (POST /api/v1/admin/coupons)
  //   - usageLimit: null → 무제한 (단일사용 직렬화 경합 시나리오 — 한도형 아님)
  //   - isActive: true → 즉시 활성
  //   - startsAt/endsAt: 과거~미래 → now 항상 포함
  //   - discountType: "fixed", value: "1000", minOrderAmount: "0"
  // 런별 유니크 code: COUPON_CODE_PREFIX + prefix (RUN_TAG 또는 uuidv4)
  const prefix = __ENV.RUN_TAG || uuidv4();
  const couponCode = `${SEED.COUPON_CODE_PREFIX}-${prefix}`;
  const couponName = `PERF-Coupon-${prefix}`;

  const couponCreateRes = http.post(
    `${baseUrl}/api/v1/admin/coupons`,
    JSON.stringify({
      code: couponCode,
      name: couponName,
      discountType: SEED.COUPON_DISCOUNT_TYPE,
      value: SEED.COUPON_VALUE,
      minOrderAmount: SEED.COUPON_MIN_ORDER_AMOUNT,
      startsAt: SEED.COUPON_STARTS_AT,
      endsAt: SEED.COUPON_ENDS_AT,
      usageLimit: SEED.COUPON_USAGE_LIMIT,
      isActive: true,
    }),
    authHeaders(adminToken),
  );

  if (couponCreateRes.status !== 201) {
    throw new Error(
      `[seed.setupCouponSeed] 쿠폰 생성 실패 — status=${couponCreateRes.status} body=${couponCreateRes.body}`,
    );
  }

  // 11. 각 buyer가 쿠폰을 1회 발급 (POST /api/v1/coupons {code})
  //   - UNIQUE(user_id, coupon_id) 제약: 같은 buyer가 같은 쿠폰 재발급 → 409
  //   - 여기서는 buyer당 1회만 발급하므로 409 없음
  const buyers = seed.buyers.map((buyer, i) => {
    const issueRes = http.post(
      `${baseUrl}/api/v1/coupons`,
      JSON.stringify({ code: couponCode }),
      authHeaders(buyer.token),
    );

    if (issueRes.status !== 201) {
      throw new Error(
        `[seed.setupCouponSeed] buyer[${i}] 쿠폰 발급 실패 — email=${buyer.email} status=${issueRes.status} body=${issueRes.body}`,
      );
    }

    const issueBody = JSON.parse(issueRes.body);
    if (!issueBody.userCouponId) {
      throw new Error(
        `[seed.setupCouponSeed] buyer[${i}] userCouponId 미존재 — email=${buyer.email} body=${issueRes.body}`,
      );
    }

    // buyer 객체에 userCouponId 추가 (기존 필드 전부 유지)
    return {
      ...buyer,
      userCouponId: issueBody.userCouponId,
    };
  });

  return {
    variantId: seed.variantId,
    buyers,
  };
}
