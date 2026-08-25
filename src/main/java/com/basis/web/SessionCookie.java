package com.basis.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The one cookie, holding an opaque id and a signature over it.
 *
 * <p>The id is already 256 random bits, so it cannot be guessed and the signature is not what
 * keeps somebody out of another person's session. What the signature buys is a clean
 * rejection: a tampered or truncated cookie is discarded as invalid rather than being looked
 * up, which keeps forged values out of the store's keyspace and out of the logs.
 *
 * <p>The signing key is generated at startup and never written down. A restart invalidates
 * every outstanding cookie, which is exactly right here: the sessions those cookies pointed
 * at were in memory and are gone too, so a cookie that outlived them would only produce a
 * confusing empty page.
 *
 * <p>HttpOnly, SameSite=Lax, and Secure whenever the request arrived over HTTPS. Not
 * unconditionally Secure, because that would break running it on localhost, which is how
 * anybody developing this will meet it first.
 */
@Component
public class SessionCookie {

    public static final String NAME = "basis_session";

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public SessionCookie() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        this.key = new SecretKeySpec(secret, ALGORITHM);
    }

    public void write(HttpServletResponse response, String sessionId, boolean secure, int maxAgeSeconds) {
        Cookie cookie = new Cookie(NAME, sessionId + "." + sign(sessionId));
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setMaxAge(maxAgeSeconds);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    /** Expires the cookie. Paired with deleting the session, never used on its own. */
    public void clear(HttpServletResponse response, boolean secure) {
        Cookie cookie = new Cookie(NAME, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (!NAME.equals(cookie.getName())) {
                continue;
            }
            return verify(cookie.getValue());
        }
        return Optional.empty();
    }

    private Optional<String> verify(String value) {
        int dot = value == null ? -1 : value.lastIndexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String id = value.substring(0, dot);
        String presented = value.substring(dot + 1);
        // Constant time, so the comparison cannot be used to discover a valid signature
        // one character at a time.
        return java.security.MessageDigest.isEqual(
                        sign(id).getBytes(StandardCharsets.UTF_8),
                        presented.getBytes(StandardCharsets.UTF_8))
                ? Optional.of(id)
                : Optional.empty();
    }

    private String sign(String id) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(id.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign the session cookie", e);
        }
    }
}
