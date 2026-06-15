/* review-form.js — 리뷰 작성/수정 폼 전용 스크립트 */
(function () {
    'use strict';

    /**
     * 리뷰 내용(textarea) 글자 수 카운터.
     * content 입력 시 실시간으로 #content-count span을 업데이트한다.
     */
    document.addEventListener('DOMContentLoaded', function () {
        var contentTextarea = document.getElementById('content');
        var countSpan = document.getElementById('content-count');

        if (contentTextarea && countSpan) {
            // 초기 값 반영 (수정 시 기존 내용 길이 표시)
            countSpan.textContent = contentTextarea.value.length;

            contentTextarea.addEventListener('input', function () {
                countSpan.textContent = contentTextarea.value.length;
            });
        }
    });
})();
