-- RTR 재사용 유예(reuse interval) 도입 — 소진 시각 기록용 used_at 추가.
-- FE 가 갱신 응답을 유실하고 같은 refresh 로 재시도하는 경우(single-flight 로도 못 막는 케이스)를
-- 탈취 오판(family 전체 철회)에서 구제한다: 소진 후 유예(기본 15초) 내 재제시는 철회 대신 한 번 더 회전.
-- 기존 used 행은 used_at 이 NULL — 유예 판정 불가이므로 종전대로 재사용 취급(안전한 쪽).

ALTER TABLE refresh_token ADD COLUMN used_at TIMESTAMP;
