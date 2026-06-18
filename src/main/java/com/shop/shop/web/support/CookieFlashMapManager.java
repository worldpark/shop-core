package com.shop.shop.web.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.lang.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.AbstractFlashMapManager;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AbstractFlashMapManager} 구현체 — PRG flash 메시지를 HTTP 세션 대신 쿠키에 저장한다.
 *
 * <p>저장 형식: {@code List<FlashMap>} → JSON 배열 → Base64 URL-safe 인코딩 → 쿠키 값.
 * 각 FlashMap 원소는 {@code { attrs, path, params, exp }} 구조로 직렬화된다.
 *
 * <p>쿠키 속성:
 * <ul>
 *   <li>이름: {@code FLASH}</li>
 *   <li>Path: {@code /}</li>
 *   <li>HttpOnly: true (JS 접근 불요 — SSR 렌더 전용)</li>
 *   <li>SameSite: Lax (redirect-after-POST의 top-level GET에 쿠키 전송)</li>
 *   <li>Secure: true (052 CSRF/세션 쿠키 정책과 일치)</li>
 * </ul>
 *
 * <p>SameSite 작성: {@code jakarta.servlet.http.Cookie}는 SameSite 미지원 →
 * {@link ResponseCookie} 빌더로 구성하고 {@code Set-Cookie} 헤더로 직접 기록.
 *
 * <p>소비 후 정리: 빈 리스트 → maxAge=0으로 쿠키 즉시 만료. 다음 요청에 쿠키 미전송 → 1회성 보장.
 *
 * <p>파싱 실패: debug 로그 + null 반환(fail-safe). flash는 1회성 UI 힌트로 손실돼도 기능 안전.
 */
@RequiredArgsConstructor
public class CookieFlashMapManager extends AbstractFlashMapManager {

    static final String COOKIE_NAME = "FLASH";
    private static final int COOKIE_SIZE_LIMIT = 4096;

    private final ObjectMapper objectMapper;

    /**
     * 요청의 {@code FLASH} 쿠키에서 {@code List<FlashMap>}을 복원한다.
     *
     * <p>쿠키가 없으면 null. 파싱 실패 시 debug 로그 + null(예외 미전파).
     * 이 메서드는 response를 받지 않으므로 Set-Cookie 불가 — 좀비 쿠키 정리는 updateFlashMaps 경로에서만.
     */
    @Override
    @Nullable
    protected List<FlashMap> retrieveFlashMaps(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value == null || value.isBlank()) {
                    return null;
                }
                return deserialize(value);
            }
        }
        return null;
    }

    /**
     * {@code List<FlashMap>}을 {@code FLASH} 쿠키에 저장한다.
     *
     * <p>빈 리스트 → maxAge=0으로 즉시 만료(소비 완료 반영). 비어있지 않으면 직렬화 후 저장.
     * 직렬화 결과가 쿠키 4KB 한계에 근접하면 저장 생략 + debug 로그(깨진 헤더보다 flash drop이 안전).
     */
    @Override
    protected void updateFlashMaps(List<FlashMap> flashMaps,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        if (flashMaps.isEmpty()) {
            writeCookie(response, "", 0);
            return;
        }
        String serialized = serialize(flashMaps);
        if (serialized == null) {
            return;
        }
        if (serialized.length() > COOKIE_SIZE_LIMIT) {
            logger.debug("FLASH 쿠키 직렬화 결과가 4KB 한계를 초과 — flash 저장 생략(drop). 단문 flash를 사용하라.");
            return;
        }
        writeCookie(response, serialized, -1);
    }

    /**
     * 세션에 의존하지 않으므로 mutex 없이 동작한다.
     * 상위 {@link AbstractFlashMapManager}는 null 반환 시 동기화 없이 처리한다.
     */
    @Override
    @Nullable
    protected Object getFlashMapsMutex(HttpServletRequest request) {
        return null;
    }

    // ========================================================
    // 직렬화 / 역직렬화
    // ========================================================

    /**
     * {@code List<FlashMap>}을 JSON → Base64 URL-safe 문자열로 직렬화한다.
     * 실패 시 debug 로그 + null.
     */
    @Nullable
    private String serialize(List<FlashMap> flashMaps) {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (FlashMap flashMap : flashMaps) {
                Map<String, Object> entry = new HashMap<>();

                // attrs: 비-String 값 방어 — 현재 전부 String이나 미래 회귀 방지
                Map<String, String> attrs = new HashMap<>();
                flashMap.forEach((k, v) -> attrs.put(k, String.valueOf(v)));
                entry.put("attrs", attrs);

                entry.put("path", flashMap.getTargetRequestPath());
                entry.put("params", flashMap.getTargetRequestParams());
                entry.put("exp", flashMap.getExpirationTime());

                list.add(entry);
            }
            byte[] json = objectMapper.writeValueAsBytes(list);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            logger.debug("FLASH 쿠키 직렬화 실패", e);
            return null;
        }
    }

    /**
     * Base64 URL-safe 문자열 → JSON → {@code List<FlashMap>}으로 역직렬화한다.
     * 파싱 실패 시 debug 로그 + null(예외 미전파).
     */
    @Nullable
    private List<FlashMap> deserialize(String cookieValue) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(cookieValue);
            List<Map<String, Object>> list = objectMapper.readValue(
                    json,
                    new TypeReference<List<Map<String, Object>>>() {}
            );
            List<FlashMap> result = new ArrayList<>();
            for (Map<String, Object> entry : list) {
                FlashMap flashMap = new FlashMap();

                // attrs 복원
                @SuppressWarnings("unchecked")
                Map<String, String> attrs = (Map<String, String>) entry.get("attrs");
                if (attrs != null) {
                    flashMap.putAll(attrs);
                }

                // targetRequestPath 복원
                Object path = entry.get("path");
                if (path instanceof String s) {
                    flashMap.setTargetRequestPath(s);
                }

                // targetRequestParams 복원
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) entry.get("params");
                if (params != null) {
                    MultiValueMap<String, String> mvm = new LinkedMultiValueMap<>();
                    params.forEach((k, v) -> {
                        if (v instanceof List<?> values) {
                            values.forEach(val -> mvm.add(k, String.valueOf(val)));
                        } else {
                            mvm.add(k, String.valueOf(v));
                        }
                    });
                    flashMap.addTargetRequestParams(mvm);
                }

                // expirationTime 복원
                Object exp = entry.get("exp");
                if (exp instanceof Number n) {
                    flashMap.setExpirationTime(n.longValue());
                }

                result.add(flashMap);
            }
            return result;
        } catch (Exception e) {
            logger.debug("FLASH 쿠키 파싱 실패 — flash 없음으로 처리(fail-safe)", e);
            return null;
        }
    }

    // ========================================================
    // 쿠키 Set-Cookie 헤더 작성
    // ========================================================

    /**
     * {@link ResponseCookie} 빌더로 SameSite=Lax 포함 Set-Cookie 헤더를 기록한다.
     *
     * <p>{@code jakarta.servlet.http.Cookie}는 Servlet 6.1 미만에서 SameSite 미지원 →
     * Spring의 {@link ResponseCookie}를 사용해 헤더 문자열을 직접 생성·기록한다.
     *
     * @param response 현재 응답
     * @param value    쿠키 값 (만료 시 빈 문자열)
     * @param maxAge   초 단위. 0이면 즉시 만료, -1이면 세션 쿠키(브라우저 닫을 때 삭제).
     */
    private void writeCookie(HttpServletResponse response, String value, int maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, value)
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .secure(true)
                .maxAge(maxAge)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }
}
