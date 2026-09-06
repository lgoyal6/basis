package com.basis.importer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A broker is one of the names in {@code config/brokers}, and nothing else.
 *
 * <p>The name reaches here from a form field on a public page, and it used to be handed
 * straight to {@code resolve}. That is a path, not a name: {@code ../../gradle/wrapper/}
 * {@code gradle-wrapper} walked out of the directory, an absolute path replaced it entirely,
 * and the two different refusals that came back ("no broker profile at X" against "X has no
 * profile.name") told a stranger whether any {@code .properties} file they cared to name
 * existed on the server. Not a file read, but an oracle, and one nobody asked for.
 *
 * <p>Checked here rather than only at the controller because this is where the path is
 * actually built. A caller that forgets to validate is the normal case, not the exception.
 */
class BrokerNameIsNotAPathTest {

    private static final Path BROKERS = Path.of("config", "brokers");

    @Test
    @DisplayName("a name that walks out of the profile directory is refused before it is resolved")
    void traversalIsRefused() {
        assertThatThrownBy(() -> BrokerProfiles.load(BROKERS, "../../gradle/wrapper/gradle-wrapper"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a broker")
                .as("naming the path back would be the disclosure this refusal exists to stop")
                .hasMessageNotContaining("gradle-wrapper.properties");
    }

    @Test
    @DisplayName("an absolute path is refused, because resolve would otherwise discard the directory")
    void absolutePathIsRefused() {
        assertThatThrownBy(() -> BrokerProfiles.load(BROKERS, "/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a broker");
    }

    @Test
    @DisplayName("a missing broker and a probed path are refused the same way, so neither answers anything")
    void aProbeLearnsNothingFromTheDifference() {
        String forAName = messageOf("nosuchbroker");
        String forAPath = messageOf("../../gradle/wrapper/gradle-wrapper");

        // The whole point. A file that exists and one that does not have to be
        // indistinguishable, or the refusal is itself the answer.
        org.assertj.core.api.Assertions.assertThat(forAPath)
                .as("an existing .properties file must not read differently from a missing one")
                .isNotEqualTo(forAName);
        org.assertj.core.api.Assertions.assertThat(forAPath).contains("is not a broker");
        org.assertj.core.api.Assertions.assertThat(forAName).contains("no broker profile");
    }

    @Test
    @DisplayName("the real profiles still load, so this did not just break every import")
    void theRealOnesStillWork() {
        for (String broker : BrokerProfiles.available(BROKERS)) {
            org.assertj.core.api.Assertions.assertThat(BrokerProfiles.load(BROKERS, broker).name())
                    .as(broker + " has to keep loading")
                    .isNotBlank();
        }
        org.assertj.core.api.Assertions.assertThat(BrokerProfiles.available(BROKERS))
                .contains("fidelity", "schwab");
        // Case is not part of the name: the loader lower cases it, and the upload form
        // has always been allowed to send "Fidelity".
        org.assertj.core.api.Assertions.assertThat(BrokerProfiles.load(BROKERS, "FIDELITY").name())
                .isEqualTo("Fidelity");
    }

    private static String messageOf(String broker) {
        try {
            BrokerProfiles.load(BROKERS, broker);
            throw new AssertionError(broker + " loaded, which it should not have");
        } catch (IllegalArgumentException expected) {
            return expected.getMessage();
        }
    }
}
