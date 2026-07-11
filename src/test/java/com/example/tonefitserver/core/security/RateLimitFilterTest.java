package com.example.tonefitserver.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 클라이언트 IP 판별 단위 테스트 — CloudFront-Viewer-Address 우선·XFF 첫 값 위조 차단 검증.
 */
class RateLimitFilterTest {

    @Test
    @DisplayName("CloudFront-Viewer-Address(IPv4:port) 가 있으면 그 IP — XFF 는 무시")
    void viewerAddressWins() {
        String ip = RateLimitFilter.resolveClientIp("198.51.100.10:46532", "1.1.1.1, 9.9.9.9", "127.0.0.1");
        assertThat(ip).isEqualTo("198.51.100.10");
    }

    @Test
    @DisplayName("IPv6 무괄호 'addr:port' — 마지막 콜론 뒤 포트만 제거")
    void viewerAddressIpv6Unbracketed() {
        assertThat(RateLimitFilter.stripPort("2001:db8:85a3::8a2e:370:7334:41321"))
                .isEqualTo("2001:db8:85a3::8a2e:370:7334");
    }

    @Test
    @DisplayName("'[IPv6]:port' 괄호 형식 — 괄호 안만 추출")
    void viewerAddressIpv6Bracketed() {
        assertThat(RateLimitFilter.stripPort("[2001:db8::1]:443")).isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("Viewer-Address 부재 시 XFF 최우측(CloudFront append 값) — 위조된 첫 값 무시")
    void xffFallbackUsesRightmost() {
        String ip = RateLimitFilter.resolveClientIp(null, "1.1.1.1, 203.0.113.7", "127.0.0.1");
        assertThat(ip).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("헤더가 모두 없으면 remoteAddr")
    void remoteAddrFallback() {
        assertThat(RateLimitFilter.resolveClientIp(null, null, "10.0.0.5")).isEqualTo("10.0.0.5");
    }
}
