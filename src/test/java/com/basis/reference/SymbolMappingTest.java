package com.basis.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Commodity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hand maintained ticker rename file.
 *
 * <p>Hand maintained because the provider's symbol change endpoint answered HTTP 402 in the
 * week 0 probe. A human edits this file, so the parser is forgiving about comments and
 * unforgiving about typos: a malformed line names its line number rather than being skipped,
 * because a silently skipped row costs someone the rename that explains their break.
 */
class SymbolMappingTest {

    @Nested
    class Resolution {

        @Test
        @DisplayName("an old ticker resolves to what it is called today")
        void resolvesARename() {
            SymbolMapping mapping = SymbolMapping.of(List.of(
                    new SymbolChange("FB", "META", LocalDate.of(2022, 6, 9), "")));

            assertThat(mapping.currentSymbol("FB")).isEqualTo("META");
            assertThat(mapping.currentSymbol("META")).isEqualTo("META");
            assertThat(mapping.currentSymbol("AAPL"))
                    .as("a ticker nobody renamed is left alone")
                    .isEqualTo("AAPL");
        }

        @Test
        @DisplayName("a ticker renamed twice resolves all the way to the end of the chain")
        void resolvesTransitively() {
            SymbolMapping mapping = SymbolMapping.of(List.of(
                    new SymbolChange("AAA", "BBB", LocalDate.of(2020, 1, 1), ""),
                    new SymbolChange("BBB", "CCC", LocalDate.of(2022, 1, 1), "")));

            assertThat(mapping.currentSymbol("AAA")).isEqualTo("CCC");
            assertThat(mapping.chainFrom("AAA")).hasSize(2);
            assertThat(mapping.lastChangeFor("AAA")).get()
                    .satisfies(change -> assertThat(change.to()).isEqualTo("CCC"));
        }

        @Test
        @DisplayName("two names for the same company are recognised as the same company")
        void recognisesTwoNamesForOneThing() {
            SymbolMapping mapping = SymbolMapping.of(List.of(
                    new SymbolChange("FB", "META", LocalDate.of(2022, 6, 9), "")));

            assertThat(mapping.renamedTo("FB", "META")).isTrue();
            assertThat(mapping.renamedTo("META", "FB"))
                    .as("resolution runs one way, but both names land on the same current one")
                    .isTrue();
            assertThat(mapping.renamedTo("FB", "AAPL")).isFalse();
            assertThat(mapping.renamedTo("AAPL", "AAPL")).isFalse();
        }

        @Test
        @DisplayName("resolving a commodity keeps its class")
        void resolvingKeepsTheCommodityClass() {
            SymbolMapping mapping = SymbolMapping.of(List.of(
                    new SymbolChange("FB", "META", LocalDate.of(2022, 6, 9), "")));

            assertThat(mapping.resolve(Commodity.equity("FB")))
                    .isEqualTo(Commodity.equity("META"));
            Commodity unchanged = Commodity.equity("AAPL");
            assertThat(mapping.resolve(unchanged))
                    .as("an unchanged ticker comes back untouched")
                    .isSameAs(unchanged);
        }

        @Test
        @DisplayName("the effective date decides whether a statement should already use the new name")
        void datesAreLoadBearing() {
            SymbolMapping mapping = SymbolMapping.of(List.of(
                    new SymbolChange("FB", "META", LocalDate.of(2022, 6, 9), "")));

            assertThat(mapping.appliesBy("FB", LocalDate.of(2022, 6, 9))).isTrue();
            assertThat(mapping.appliesBy("FB", LocalDate.of(2022, 6, 8)))
                    .as("a statement from the day before should still say FB")
                    .isFalse();
        }
    }

    @Nested
    class Validation {

        @Test
        @DisplayName("a ticker cannot have become two different things")
        void refusesADuplicateSource() {
            assertThatThrownBy(() -> SymbolMapping.of(List.of(
                    new SymbolChange("AAA", "BBB", LocalDate.of(2020, 1, 1), ""),
                    new SymbolChange("AAA", "CCC", LocalDate.of(2021, 1, 1), ""))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mapped twice");
        }

        @Test
        @DisplayName("a rename chain that loops is caught at load, not at lookup")
        void refusesACycle() {
            assertThatThrownBy(() -> SymbolMapping.of(List.of(
                    new SymbolChange("AAA", "BBB", LocalDate.of(2020, 1, 1), ""),
                    new SymbolChange("BBB", "CCC", LocalDate.of(2021, 1, 1), ""),
                    new SymbolChange("CCC", "AAA", LocalDate.of(2022, 1, 1), ""))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("loops");
        }

        @Test
        @DisplayName("a ticker cannot be renamed to itself")
        void refusesASelfRename() {
            assertThatThrownBy(() -> new SymbolChange("AAA", "AAA", LocalDate.of(2020, 1, 1), ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be renamed to itself");
        }

        @Test
        @DisplayName("tickers are upper cased, so case in the file does not matter")
        void normalisesCase() {
            SymbolChange change = new SymbolChange(" fb ", "meta", LocalDate.of(2022, 6, 9), " note ");

            assertThat(change.from()).isEqualTo("FB");
            assertThat(change.to()).isEqualTo("META");
            assertThat(change.note()).isEqualTo("note");
        }
    }

    @Nested
    class FileParsing {

        @Test
        @DisplayName("comments, blanks and the header are ignored")
        void ignoresTheFurniture() {
            SymbolMapping mapping = SymbolMappingFile.parse(List.of(
                    "# a comment",
                    "",
                    "old_symbol,new_symbol,effective_date,note",
                    "FB,META,2022-06-09,Facebook Inc renamed",
                    "   ",
                    "# another comment"), "test");

            assertThat(mapping.size()).isEqualTo(1);
            assertThat(mapping.currentSymbol("FB")).isEqualTo("META");
        }

        @Test
        @DisplayName("the note is optional")
        void noteIsOptional() {
            assertThat(SymbolMappingFile.parse(List.of("FB,META,2022-06-09"), "test").size()).isEqualTo(1);
        }

        @Test
        @DisplayName("a malformed line names its line number rather than being skipped")
        void malformedLinesAreLoud() {
            assertThatThrownBy(() -> SymbolMappingFile.parse(List.of(
                    "FB,META,2022-06-09",
                    "GOOG,2015-10-02"), "renames.csv"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("renames.csv:2")
                    .hasMessageContaining("expected old_symbol,new_symbol,effective_date");
        }

        @Test
        @DisplayName("a bad date names its line number too")
        void badDatesAreLoud() {
            assertThatThrownBy(() -> SymbolMappingFile.parse(List.of("FB,META,June 9th"), "renames.csv"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("renames.csv:1")
                    .hasMessageContaining("yyyy-mm-dd");
        }

        @Test
        @DisplayName("a missing file is not an error, because most histories have no renames")
        void aMissingFileIsFine(@TempDir Path directory) {
            SymbolMapping mapping = SymbolMappingFile.load(directory.resolve("does-not-exist.csv"));

            assertThat(mapping.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the file that ships with the repository parses and is not empty")
        void theShippedFileIsValid() {
            Path shipped = Path.of("config/symbol-changes.csv");
            assertThat(Files.exists(shipped))
                    .as("the reconciler tells users this file exists, so it had better")
                    .isTrue();

            SymbolMapping mapping = SymbolMappingFile.load(shipped);

            assertThat(mapping.currentSymbol("FB")).isEqualTo("META");
        }
    }
}
