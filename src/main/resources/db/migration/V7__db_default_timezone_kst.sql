-- DB 세션 기본 타임존을 KST(Asia/Seoul)로 설정.
--
-- timestamptz 컬럼에 저장된 "절대시각"은 변하지 않는다(데이터 무손실·비파괴).
-- 변하는 것은 세션 기본 표시 타임존뿐 — 새 세션에서 timestamptz 조회·now() 기본 렌더가 KST로 보인다.
-- (psql·DB 툴에서 created_at 등이 KST로 표시됨. 앱은 Instant 매핑이라 동작 영향 없음.)
--
-- 적용 시점: 본 설정은 "새로 맺는 세션"부터 적용된다. 앱 커넥션 풀은 재기동 후 반영.
-- 환경별 DB명에 무관하도록 Flyway 플레이스홀더(${flyway:database})로 현재 DB에 적용한다.
ALTER DATABASE "${flyway:database}" SET timezone TO 'Asia/Seoul';
