/* app.js — shop-core 공통 스크립트 */
(function () {
    'use strict';

    /*
     * Flash 메시지 토스트화.
     *
     * 서버는 redirect 후 .flash-region 안에 .alert(.alert-success / .alert-error)를
     * 인라인으로 렌더한다. 이를 화면 우측 상단에 잠깐 떴다가 자동으로 사라지는
     * 토스트로 변환한다(작업 흐름을 막지 않는 비차단 알림).
     *
     * 적용 범위: base 레이아웃을 쓰는 모든 화면(상품 등록/수정 성공, Variant 검증 실패 등).
     * JS 미동작 시에도 인라인 .alert가 그대로 보이므로 메시지 자체는 유실되지 않는다.
     */
    var TOAST_DURATION_MS = 3000;   // 표시 유지 시간
    var TOAST_FADE_MS = 300;        // 사라질 때 페이드 시간

    function ensureToastContainer() {
        var container = document.querySelector('.toast-container');
        if (!container) {
            container = document.createElement('div');
            container.className = 'toast-container';
            document.body.appendChild(container);
        }
        return container;
    }

    function dismissToast(toast) {
        if (toast.dataset.dismissing === 'true') { return; }
        toast.dataset.dismissing = 'true';
        toast.classList.add('toast-hide');
        window.setTimeout(function () {
            if (toast.parentNode) { toast.parentNode.removeChild(toast); }
        }, TOAST_FADE_MS);
    }

    function showToast(message, kind) {
        var container = ensureToastContainer();

        var toast = document.createElement('div');
        toast.className = 'toast toast-' + kind;
        toast.setAttribute('role', kind === 'error' ? 'alert' : 'status');

        var text = document.createElement('span');
        text.className = 'toast-text';
        text.textContent = message;
        toast.appendChild(text);

        // 클릭하면 즉시 닫기
        toast.addEventListener('click', function () { dismissToast(toast); });

        container.appendChild(toast);

        // 진입 애니메이션 트리거(다음 프레임에 표시 클래스 부여)
        window.requestAnimationFrame(function () {
            toast.classList.add('toast-show');
        });

        window.setTimeout(function () { dismissToast(toast); }, TOAST_DURATION_MS);
    }

    function convertFlashMessagesToToasts() {
        var region = document.querySelector('.flash-region');
        if (!region) { return; }

        var alerts = region.querySelectorAll('.alert');
        alerts.forEach(function (alertEl) {
            var message = (alertEl.textContent || '').trim();
            if (message === '') { return; }
            var kind = alertEl.classList.contains('alert-error') ? 'error' : 'success';
            showToast(message, kind);
        });

        // 인라인 배너 제거(토스트로 대체했으므로 중복 표시 방지)
        region.innerHTML = '';
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', convertFlashMessagesToToasts);
    } else {
        convertFlashMessagesToToasts();
    }

    /*
     * 상품 상세 이미지 갤러리: 썸네일 클릭 → 메인 이미지 교체 + active 표시 이동.
     *
     * 썸네일(.gallery-thumb)에는 서버가 data-src(원본 이미지 URL)를 심어둔다.
     * 클릭 위임으로 처리하므로 갤러리가 없는 페이지에서는 자동으로 무동작이다.
     */
    document.addEventListener('click', function (e) {
        var thumb = e.target.closest('.gallery-thumb');
        if (!thumb) { return; }

        var main = document.getElementById('main-product-image');
        var src = thumb.getAttribute('data-src');
        if (main && src) { main.src = src; }

        document.querySelectorAll('.gallery-thumb').forEach(function (t) {
            t.classList.toggle('thumb-active', t === thumb);
        });
    });
})();
