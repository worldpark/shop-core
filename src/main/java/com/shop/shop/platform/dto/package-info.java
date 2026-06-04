/**
 * platform 모듈 — DTO 레이어.
 *
 * <p>REST 응답 전용 DTO를 담는다. Entity·이벤트를 직접 반환하지 않는 원칙에 따라
 * Controller에 노출되는 모든 응답 타입이 이 레이어에 위치한다.
 *
 * <p>가드레일:
 * <ul>
 *   <li>Entity를 API 응답으로 직접 반환하지 않는다.</li>
 *   <li>이벤트 record를 Controller에 직접 노출하지 않는다.</li>
 * </ul>
 */
package com.shop.shop.platform.dto;
