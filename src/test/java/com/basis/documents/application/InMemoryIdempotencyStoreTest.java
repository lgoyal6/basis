package com.basis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class InMemoryIdempotencyStoreTest {
    @Test void claimsAreUniquePerTenant() {
        var store = new InMemoryIdempotencyStore();
        assertThat(store.claim("a", "k")).isTrue();
        assertThat(store.claim("a", "k")).isFalse();
        assertThat(store.claim("b", "k")).isTrue();
    }
}
