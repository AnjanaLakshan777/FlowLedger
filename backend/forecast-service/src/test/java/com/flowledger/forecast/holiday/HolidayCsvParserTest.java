package com.flowledger.forecast.holiday;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class HolidayCsvParserTest {

    @Test
    void shouldReadTheCentralBanksCodesAndFindPoyaDays() {
        HolidayCsvParser.Result result = parse("""
                date,name,codes
                2027-05-19,Vesak Full Moon Poya Day,B. P.
                2027-05-20,Day following Vesak Full Moon Poya Day,B. P. M.
                2027-10-28,"Deepawali Festival Day",B P
                2027-12-25,Christmas Day,bpm
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).hasSize(4);
        HolidayCsvParser.Row vesak = result.rows().getFirst();
        assertThat(vesak.date()).isEqualTo(LocalDate.of(2027, 5, 19));
        assertThat(vesak.bank()).isTrue();
        assertThat(vesak.publicHoliday()).isTrue();
        assertThat(vesak.mercantile()).isFalse();
        assertThat(vesak.poya()).isTrue();
        assertThat(result.rows().get(1).poya()).isFalse();
        assertThat(result.rows().get(1).mercantile()).isTrue();
        assertThat(result.rows().get(2).name()).isEqualTo("Deepawali Festival Day");
        assertThat(result.rows().get(3).mercantile()).isTrue();
    }

    @Test
    void shouldRefuseTheWholeFileWithEveryProblem() {
        HolidayCsvParser.Result result = parse("""
                date,name,codes
                2026-12-25,Christmas Day,B P M
                12/25/2027,Christmas Day,B P M
                2027-01-15,,B P M
                2027-02-04,Independence Day,X
                2027-02-04,Independence Day,B
                2027-02-04,Independence Day,B
                """);

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).hasSize(5)
                .anySatisfy(e -> assertThat(e).contains("Line 2").contains("is not in 2027"))
                .anySatisfy(e -> assertThat(e).contains("Line 3").contains("YYYY-MM-DD"))
                .anySatisfy(e -> assertThat(e).contains("Line 4").contains("name"))
                .anySatisfy(e -> assertThat(e).contains("Line 5").contains("codes"))
                .anySatisfy(e -> assertThat(e).contains("Line 7").contains("listed twice"));
    }

    @Test
    void shouldRequireTheHeaderAndSomeHolidays() {
        assertThat(parse("day,name\n2027-01-01,X,B\n").errors()).containsExactly("The first row must be the header: date,name,codes.");
        assertThat(parse("date,name,codes\n").errors()).containsExactly("The file lists no holidays.");
        assertThat(parse("").errors()).containsExactly("The file is empty.");
    }

    private static HolidayCsvParser.Result parse(String content) {
        return HolidayCsvParser.parse(content.getBytes(StandardCharsets.UTF_8), 2027);
    }
}
