package com.basis.domain;

import static com.basis.support.Fixtures.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The arithmetic everything else stands on. */
class ValueTypeTest {

    @Nested
    class MoneyTest {

        @Test
        @DisplayName("a major unit amount that is not whole cents is refused, not rounded")
        void refusesInexactMajorUnits() {
            assertThatThrownBy(() -> Money.of(new BigDecimal("1.005"), USD))
                    .isInstanceOf(ArithmeticException.class)
                    .hasMessageContaining("not exact");
        }

        @Test
        @DisplayName("rounding is HALF_EVEN, so a half cent does not always go up")
        void roundsHalfEven() {
            assertThat(Money.round(new BigDecimal("1.005"), USD)).isEqualTo(Money.ofMinor(100, USD));
            assertThat(Money.round(new BigDecimal("1.015"), USD)).isEqualTo(Money.ofMinor(102, USD));
        }

        @Test
        @DisplayName("a currency with no minor unit is whole units, not two decimals")
        void zeroDecimalCurrency() {
            Currency jpy = Currency.getInstance("JPY");

            assertThat(Money.of(new BigDecimal("500"), jpy).minorUnits()).isEqualTo(500L);
            assertThat(Money.ofMinor(500, jpy).toMajorUnits()).isEqualByComparingTo("500");
        }

        @Test
        @DisplayName("adding a different currency is refused rather than coerced")
        void refusesMixedCurrencyArithmetic() {
            assertThatThrownBy(() -> Money.ofMinor(100, USD)
                    .plus(Money.ofMinor(100, Currency.getInstance("EUR"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currency mismatch");
        }

        @Test
        @DisplayName("overflow throws rather than wrapping around")
        void overflowThrows() {
            Money huge = Money.ofMinor(Long.MAX_VALUE, USD);

            assertThatThrownBy(() -> huge.plus(Money.ofMinor(1, USD)))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        @DisplayName("a major unit round trip is lossless")
        void majorUnitRoundTrip() {
            Money money = Money.ofMinor(-150125, USD);

            assertThat(money.toMajorUnits()).isEqualByComparingTo("-1501.25");
            assertThat(Money.of(money.toMajorUnits(), USD)).isEqualTo(money);
        }
    }

    @Nested
    class QuantityTest {

        @Test
        @DisplayName("equality ignores how the number was written")
        void canonicalisesScaleSoEqualityWorks() {
            assertThat(Quantity.of("10")).isEqualTo(Quantity.of("10.00000000"));
            assertThat(Quantity.of(10)).isEqualTo(Quantity.of(new BigDecimal("10.000")));
            assertThat(Quantity.of("10")).hasSameHashCodeAs(Quantity.of("10.00000000"));
        }

        @Test
        @DisplayName("scale 8 holds a fractional share, and rounds beyond it HALF_EVEN")
        void holdsFractionalSharesAtScaleEight() {
            assertThat(Quantity.of("0.00000001").value()).isEqualByComparingTo("0.00000001");
            assertThat(Quantity.of("0.000000005").value())
                    .as("a half unit at scale 9 goes to even, which is zero")
                    .isEqualByComparingTo("0");
            assertThat(Quantity.of("0.000000015").value()).isEqualByComparingTo("0.00000002");
        }

        @Test
        @DisplayName("multiplying by a price is exact, with no intermediate rounding")
        void multiplicationByPriceIsExact() {
            BigDecimal product = Quantity.of("0.33333333").multiplyBy(Price.of("150.00", USD));

            assertThat(product).isEqualByComparingTo("49.9999995000000000");
        }
    }

    @Nested
    class PriceTest {

        @Test
        @DisplayName("scale 6 holds a sub cent unit cost")
        void holdsSubCentUnitCosts() {
            assertThat(Price.of("0.000001", USD).value()).isEqualByComparingTo("0.000001");
            assertThat(Price.of(new BigDecimal("1.2345675"), USD).value())
                    .as("HALF_EVEN at scale 6")
                    .isEqualByComparingTo("1.234568");
        }

        @Test
        @DisplayName("comparing prices across currencies is refused")
        void refusesCrossCurrencyComparison() {
            assertThatThrownBy(() -> Price.of("1.00", USD)
                    .compareTo(Price.of("1.00", Currency.getInstance("EUR"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class AccountTest {

        @Test
        @DisplayName("the root segment must be one of the five account types")
        void rejectsUnknownRoot() {
            assertThatThrownBy(() -> Account.of("Wealth:Broker:IBKR"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("account root");
        }

        @Test
        @DisplayName("a broker is a path prefix, so a holding is a child of it")
        void holdingIsAChildOfTheBrokerRoot() {
            Account broker = Account.of("Assets:Broker:IBKR");

            assertThat(broker.child("AAPL").name()).isEqualTo("Assets:Broker:IBKR:AAPL");
            assertThat(broker.child("AAPL").isUnder(broker)).isTrue();
            assertThat(broker.type()).isEqualTo(AccountType.ASSETS);
            assertThat(broker.leaf()).isEqualTo("IBKR");
        }

        @Test
        @DisplayName("a prefix that is not a path boundary is not a parent")
        void doesNotTreatAStringPrefixAsAParent() {
            assertThat(Account.of("Assets:Broker:IBKR2").isUnder(Account.of("Assets:Broker:IBKR"))).isFalse();
        }
    }

    @Nested
    class LotTest {

        @Test
        @DisplayName("a lot cannot hold more than it acquired")
        void refusesRemainingAboveOriginal() {
            assertThatThrownBy(() -> new Lot(LotId.of("a"), Account.of("Assets:Broker:IBKR:AAPL"),
                    Commodity.equity("AAPL"), com.basis.support.Fixtures.JAN_15,
                    Price.of("150.00", USD), Quantity.of("10"), Quantity.of("11")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds acquired");
        }

        @Test
        @DisplayName("consuming more than remains is refused rather than going short")
        void refusesOverConsumption() {
            Lot lot = Lot.opened(LotId.of("a"), Account.of("Assets:Broker:IBKR:AAPL"),
                    Commodity.equity("AAPL"), com.basis.support.Fixtures.JAN_15,
                    Price.of("150.00", USD), Quantity.of("10"));

            assertThatThrownBy(() -> lot.consume(Quantity.of("11")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(lot.consume(Quantity.of("4")).remainingQuantity()).isEqualTo(Quantity.of("6"));
            assertThat(lot.consume(Quantity.of("4")).disposedQuantity()).isEqualTo(Quantity.of("4"));
        }

        @Test
        @DisplayName("cash is not held in lots")
        void refusesCashLots() {
            assertThatThrownBy(() -> Lot.opened(LotId.of("a"), Account.of("Assets:Broker:IBKR:Cash"),
                    Commodity.of(USD), com.basis.support.Fixtures.JAN_15,
                    Price.of("1.00", USD), Quantity.of("10")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not held in lots");
        }
    }

    @Nested
    class IdempotencyKeyTest {

        @Test
        @DisplayName("equal keys compare equal, despite being backed by arrays")
        void comparesByContentNotByArrayIdentity() {
            assertThat(IdempotencyKey.of("a", "b")).isEqualTo(IdempotencyKey.of("a", "b"));
            assertThat(IdempotencyKey.of("a", "b")).hasSameHashCodeAs(IdempotencyKey.of("a", "b"));
        }

        @Test
        @DisplayName("parts are length delimited, so concatenation cannot collide")
        void lengthDelimitsParts() {
            assertThat(IdempotencyKey.of("ab", "c")).isNotEqualTo(IdempotencyKey.of("a", "bc"));
        }

        @Test
        @DisplayName("the backing array is copied, so a caller cannot mutate a stored key")
        void defensivelyCopies() {
            byte[] bytes = {1, 2, 3};
            IdempotencyKey key = new IdempotencyKey(bytes);
            bytes[0] = 99;

            assertThat(key.bytes()[0]).isEqualTo((byte) 1);
        }
    }
}
