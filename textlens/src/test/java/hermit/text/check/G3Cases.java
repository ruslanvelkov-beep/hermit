package hermit.text.check;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The synthetic set G3 of the annotation plan (section 8): two hundred statements for the checkers, made by code so
 * that each expected outcome is known by construction (from java.time, exact decimals, and the counts and orders the
 * code chooses), and checked in as {@code g3-cases.txt} next to the test that runs them. A line is
 * {@code checker|clock|zone|expected|text}: the checker's name, the captured clock as an instant ({@code -} for none),
 * the document's zone, the expected status or {@code NONE} when the checker reports nothing, and the text with
 * {@code ~} for each line break. The main method prints the lines.
 */
final class G3Cases {

    private static final String CLOCK = "2026-10-28T10:00:00Z";
    private static final String[] WORDS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten"};
    private static final String[] ITEMS = {"fix the parser", "update the cache", "add a test", "rename the field",
        "drop the flag", "clean the logs", "move the helper", "split the class", "merge the branches", "sort the list"};

    private G3Cases() {}

    private static void add(List<String> lines, String checker, String clock, String zone, String expected,
            String text) {
        lines.add(String.join("|", checker, clock, zone, expected, text));
    }

    private static String day(DayOfWeek day) {
        String name = day.name();
        return name.charAt(0) + name.substring(1, 3).toLowerCase(Locale.ROOT);
    }

    private static String fullDay(DayOfWeek day) {
        return day.name().charAt(0) + day.name().substring(1).toLowerCase(Locale.ROOT);
    }

    private static String month(LocalDate date) {
        String name = date.getMonth().name();
        return name.charAt(0) + name.substring(1, 3).toLowerCase(Locale.ROOT);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String list(int items, String marker, boolean nested) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < items; i++) {
            s.append('~').append(marker).append(' ').append(ITEMS[i % ITEMS.length]);
            if (nested && i == 0) {
                s.append("~  ").append(marker).append(" with a nested note");
            }
        }
        return s.toString();
    }

    private static int versionOrder(String a, String b) {
        String[] x = a.replaceFirst("^v", "").split("\\.");
        String[] y = b.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = new BigInteger(i < x.length ? x[i] : "0").compareTo(new BigInteger(i < y.length ? y[i] : "0"));
            if (c != 0) {
                return Integer.signum(c);
            }
        }
        return 0;
    }

    private static boolean hasNumberReading(String v) {
        return v.matches("[0-9]+(\\.[0-9]+)?");
    }

    private static BigDecimal seconds(String duration) {
        String[] parts = duration.split(" ");
        BigDecimal factor = switch (parts[1]) {
            case "ms" -> new BigDecimal("0.001");
            case "seconds", "secs" -> BigDecimal.ONE;
            case "min", "minutes" -> BigDecimal.valueOf(60);
            case "hour", "hours" -> BigDecimal.valueOf(3_600);
            case "day", "days" -> BigDecimal.valueOf(86_400);
            case "week" -> BigDecimal.valueOf(604_800);
            default -> throw new IllegalArgumentException(duration);
        };
        return new BigDecimal(parts[0]).multiply(factor);
    }

    private static BigDecimal bytes(String quantity, boolean binary) {
        String[] parts = quantity.split(" ");
        int power = List.of("B", "KB", "MB", "GB", "TB").indexOf(parts[1]);
        int iec = List.of("B", "KiB", "MiB", "GiB", "TiB").indexOf(parts[1]);
        BigDecimal base = BigDecimal.valueOf(power < 0 || binary ? 1024 : 1000);
        return new BigDecimal(parts[0]).multiply(base.pow(Math.max(power, iec)));
    }

    private static void dates(List<String> lines) {
        // a date with its year, its own weekday and the next one
        for (String iso : List.of("2025-12-31", "2026-01-01", "2026-03-29", "2026-03-30", "2026-10-25", "2026-10-26",
                "2026-11-01", "2027-01-01", "2027-03-28", "2028-02-29")) {
            LocalDate date = LocalDate.parse(iso);
            for (int shift = 0; shift < 2; shift++) {
                add(lines, "date", CLOCK, "UTC", shift == 0 ? "CONSISTENT" : "INCONSISTENT", "The release was on "
                        + day(date.getDayOfWeek().plus(shift)) + " " + month(date) + " " + date.getDayOfMonth() + ", "
                        + date.getYear() + ".");
            }
        }
        // a date without a year within fifteen days of the clock: its candidate years are the clock's and, across New
        // Year, the year before (a clock in January or February, a date in October to December) or the year after (a
        // clock in November or December, a date in January or February); the past tense ("was the release") keeps the
        // years whose date is not after the clock, when there is one (the owner, 2026-11-05); it is consistent when one
        // of the kept years fits
        String[][] near = {{"2027-01-02", "2026-12-29"}, {"2026-12-30", "2027-01-03"}, {"2026-10-28", "2026-10-20"},
            {"2026-10-28", "2026-11-05"}, {"2028-01-05", "2027-12-25"}, {"2025-12-20", "2026-01-02"},
            {"2026-03-01", "2026-02-25"}, {"2028-03-02", "2028-02-29"}};
        for (String[] pair : near) {
            LocalDate date = LocalDate.parse(pair[1]);
            LocalDate clock = LocalDate.parse(pair[0]);
            List<Integer> years = new ArrayList<>(List.of(clock.getYear()));
            if (clock.getMonthValue() <= 2 && date.getMonthValue() >= 10) {
                years.add(clock.getYear() - 1);
            }
            if (clock.getMonthValue() >= 11 && date.getMonthValue() <= 2) {
                years.add(clock.getYear() + 1);
            }
            List<Integer> past = years.stream().filter(year -> !date.withYear(year).isAfter(clock)).toList();
            List<Integer> kept = past.isEmpty() ? years : past;
            for (int shift = 0; shift < 2; shift++) {
                DayOfWeek stated = date.getDayOfWeek().plus(shift);
                boolean fits = kept.stream().anyMatch(year -> date.withYear(year).getMonth() == date.getMonth()
                        && date.withYear(year).getDayOfMonth() == date.getDayOfMonth()
                        && date.withYear(year).getDayOfWeek() == stated);
                add(lines, "date", pair[0] + "T10:00:00Z", "UTC", fits ? "CONSISTENT" : "INCONSISTENT",
                        day(date.getDayOfWeek().plus(shift)) + " " + month(date) + " " + date.getDayOfMonth()
                                + " was the release.");
            }
        }
        // today and yesterday on the clock's day in the document's zone, near midnight and the clock changes
        String[][] clocks = {{"2026-03-29T21:30:00Z", "Europe/Sofia"}, {"2026-03-29T21:30:00Z", "UTC"},
            {"2026-10-25T22:30:00Z", "Europe/Sofia"}, {"2026-11-01T04:30:00Z", "America/New_York"},
            {"2026-03-08T06:30:00Z", "America/New_York"}, {"2026-12-31T23:30:00Z", "Asia/Tokyo"},
            {"2026-12-31T23:30:00Z", "America/Los_Angeles"}, {"2027-04-03T14:30:00Z", "Australia/Sydney"}};
        for (int i = 0; i < clocks.length; i++) {
            LocalDate today = LocalDate.ofInstant(Instant.parse(clocks[i][0]), ZoneId.of(clocks[i][1]));
            boolean yesterday = i % 2 == 1;
            DayOfWeek meant = (yesterday ? today.minusDays(1) : today).getDayOfWeek();
            for (int shift = 0; shift < 2; shift++) {
                add(lines, "date", clocks[i][0], clocks[i][1], shift == 0 ? "CONSISTENT" : "INCONSISTENT",
                        (yesterday ? "Yesterday was " : "Today is ") + fullDay(meant.plus(2L * shift)) + ".");
            }
        }
        // a date written with slashes, day first, and the other order when that is a date too; a weekday that fits one
        // order picks it (the owner's design of 2026-11-04)
        int[][] slashes = {{3, 14, 2026, 6}, {3, 14, 2026, 7}, {3, 4, 2026, 5}, {3, 4, 2026, 1}, {7, 7, 2026, 2},
            {12, 25, 2026, 5}};
        for (int[] s : slashes) {
            List<LocalDate> readings = new ArrayList<>(List.of(LocalDate.of(s[2], s[0], s[1])));
            if (s[1] <= 12 && s[1] != s[0]) {
                readings.add(LocalDate.of(s[2], s[1], s[0]));
            }
            DayOfWeek stated = DayOfWeek.of(s[3]);
            long holding = readings.stream().filter(d -> d.getDayOfWeek() == stated).count();
            String expected = holding > 0 ? "CONSISTENT" : "INCONSISTENT";
            add(lines, "date", CLOCK, "UTC", expected, String.format(Locale.ROOT, "On %02d/%02d/%d, a %s, we shipped.",
                    s[0], s[1], s[2], fullDay(stated)));
        }
        add(lines, "date", "-", "UTC", "UNVERIFIABLE", "We met on Mon Oct 20 to plan it.");
        add(lines, "date", "-", "UTC", "UNVERIFIABLE", "Today is Monday.");
        add(lines, "date", CLOCK, "UTC", "NONE", "The release was on Oct 20, 2026.");
        add(lines, "date", CLOCK, "UTC", "NONE", "Today we shipped the fix.");
    }

    private static void arithmetic(List<String> lines) {
        char[] ops = {'+', '-', '*', '/'};
        for (int i = 0; i < 16; i++) {
            char op = ops[i % 4];
            BigDecimal a = BigDecimal.valueOf(2 + i * 7 % 13);
            BigDecimal b = BigDecimal.valueOf(op == '/' ? new int[] {2, 4, 5, 8}[i / 4] : 1 + i * 5 % 9);
            if (i >= 8) {
                a = a.movePointLeft(1);
                b = op == '/' ? b : b.movePointLeft(2);
            }
            if (op == '-' && a.compareTo(b) < 0) {
                BigDecimal t = a;
                a = b;
                b = t;
            }
            BigDecimal c = switch (op) {
                case '+' -> a.add(b);
                case '-' -> a.subtract(b);
                case '*' -> a.multiply(b);
                default -> a.divide(b);
            };
            for (int wrong = 0; wrong < 2; wrong++) {
                add(lines, "arithmetic", CLOCK, "UTC", wrong == 0 ? "CONSISTENT" : "INCONSISTENT", "We computed "
                        + plain(a) + " " + op + " " + plain(b) + " = " + plain(c.add(BigDecimal.valueOf(wrong))) + ".");
            }
        }
        add(lines, "arithmetic", CLOCK, "UTC", "CONSISTENT", "We checked that 3 * 4 > 11.");
        add(lines, "arithmetic", CLOCK, "UTC", "INCONSISTENT", "We checked that 3 * 4 < 11.");
        add(lines, "arithmetic", CLOCK, "UTC", "CONSISTENT", "We checked that 0.1 + 0.2 >= 0.3.");
        add(lines, "arithmetic", CLOCK, "UTC", "INCONSISTENT", "We checked that 7 / 2 <= 3.");
        add(lines, "arithmetic", CLOCK, "UTC", "UNVERIFIABLE", "Then 7 / 0 = 1 is wrong.");
        add(lines, "arithmetic", CLOCK, "UTC", "UNVERIFIABLE", "Then 0 / 0 = 0 is wrong.");
        add(lines, "arithmetic", CLOCK, "UTC", "NONE", "The values 2 + 2 and 5 differ.");
        add(lines, "arithmetic", CLOCK, "UTC", "NONE", "We computed 10-27 and 03/04/2026.");
    }

    private static void counts(List<String> lines) {
        String[] nouns = {"fixes", "changes", "steps", "tests", "files", "notes", "issues", "tasks"};
        for (int n = 2; n <= 9; n++) {
            String count = n % 2 == 0 ? WORDS[n] : String.valueOf(n);
            String marker = n % 2 == 0 ? "-" : "*";
            for (int extra = 0; extra < 2; extra++) {
                add(lines, "count", CLOCK, "UTC", extra == 0 ? "CONSISTENT" : "INCONSISTENT", "The following " + count
                        + " " + nouns[n - 2] + ":" + list(n + extra, marker, false));
            }
            if (n >= 3 && n <= 6) {
                add(lines, "count", CLOCK, "UTC", "INCONSISTENT", "The following " + count + " " + nouns[n - 2] + ":"
                        + list(n - 1, marker, false));
            }
        }
        for (int n = 2; n <= 5; n++) {
            int stated = n <= 3 ? n : n + 1;
            add(lines, "count", CLOCK, "UTC", n <= 3 ? "CONSISTENT" : "INCONSISTENT", "These " + WORDS[stated]
                    + " fixes:" + list(n, "-", true));
        }
        for (String hedge : List.of("About", "Around", "Roughly", "Nearly")) {
            add(lines, "count", CLOCK, "UTC", "UNVERIFIABLE", hedge + " five changes:" + list(3, "-", false));
        }
        add(lines, "count", CLOCK, "UTC", "UNVERIFIABLE",
                "Four notes:~- the first note~- the second note~- more notes etc.");
        add(lines, "count", CLOCK, "UTC", "UNVERIFIABLE",
                "Three notes:~- the first note~- the second note~- and so on ...");
        add(lines, "count", CLOCK, "UTC", "NONE", "We made 4 fixes.");
        add(lines, "count", CLOCK, "UTC", "NONE", "We made 4 fixes:");
        add(lines, "count", CLOCK, "UTC", "NONE", "The notes:~- one note~- another note");
        add(lines, "count", CLOCK, "UTC", "NONE", "Three fixes were made.~- fix the parser");
    }

    private static void versions(List<String> lines) {
        String[][] pairs = {{"2.10", "2.9"}, {"1.9", "1.10"}, {"3.0", "2.99"}, {"1.0.1", "1.0"}, {"v3.2", "v3.10"},
            {"10", "9"}, {"2.0", "2.0.0"}, {"4.12", "4.2"}, {"1.2.10", "1.2.3"}, {"0.9", "0.10"}};
        String[] phrases = {"newer than", "older than", "later than", "earlier than", "supersedes", "precedes"};
        for (int i = 0; i < pairs.length; i++) {
            for (int turn = 0; turn < 2; turn++) {
                String a = pairs[i][turn];
                String b = pairs[i][1 - turn];
                String phrase = phrases[(2 * i + turn) % phrases.length];
                int stated = phrase.startsWith("newer") || phrase.startsWith("later") || phrase.equals("supersedes")
                        ? 1 : -1;
                String verb = phrase.endsWith("than") ? "is " + phrase : phrase;
                String cue = a.startsWith("v") ? "" : "release ";
                add(lines, "version", CLOCK, "UTC", versionOrder(a, b) == stated ? "CONSISTENT" : "INCONSISTENT",
                        "The " + cue + a + " " + verb + " " + cue + b + ".");
            }
        }
        String[][] exceeds = {{"1.10", "1.9"}, {"2.5", "2.4"}, {"2.4", "2.5"}, {"1.20", "1.3"}, {"10", "9"},
            {"9", "10"}, {"1.2.3", "1.2.10"}, {"1.2.10", "1.2.3"}};
        for (String[] pair : exceeds) {
            List<Boolean> readings = new ArrayList<>(List.of(versionOrder(pair[0], pair[1]) > 0));
            if (hasNumberReading(pair[0]) && hasNumberReading(pair[1])) {
                readings.add(new BigDecimal(pair[0]).compareTo(new BigDecimal(pair[1])) > 0);
            }
            String expected = readings.stream().allMatch(r -> r) ? "CONSISTENT"
                    : readings.stream().noneMatch(r -> r) ? "INCONSISTENT" : "UNVERIFIABLE";
            String cue = pair[0].matches("[0-9]+") ? "Version " : pair[0].split("\\.").length > 2 ? "Release " : "";
            add(lines, "version", CLOCK, "UTC", expected, cue + pair[0] + " exceeds " + cue.toLowerCase(Locale.ROOT)
                    + pair[1] + ".");
        }
        add(lines, "version", CLOCK, "UTC", "NONE", "Version 2.10 and 2.9 are both supported.");
        add(lines, "version", CLOCK, "UTC", "NONE", "The release 1.10 is newer than the old one.");
        add(lines, "version", CLOCK, "UTC", "NONE", "The release 2.10 shipped before the release 2.9.");
        add(lines, "version", CLOCK, "UTC", "NONE", "We run 3.2 and 3.10 side by side.");
    }

    private static void units(List<String> lines) {
        // durations in a mix of units, with a total that the parts add up to and one that they do not
        String[][] durations = {{"2 minutes", "90 seconds", "1 min"}, {"1 hour", "30 minutes"},
            {"500 ms", "1.5 hours", "30 secs"}, {"2 days", "12 hours"}, {"45 seconds", "15 seconds", "2 minutes"},
            {"1 week", "1 day"}};
        String[] totals = {"4.5 minutes", "1.5 hours", "5430.5 seconds", "2.5 days", "3 minutes", "8 days"};
        String[] wrong = {"5 minutes", "2 hours", "5430 seconds", "3 days", "4 minutes", "7 days"};
        for (int i = 0; i < durations.length; i++) {
            BigDecimal sum = BigDecimal.ZERO;
            for (String part : durations[i]) {
                sum = sum.add(seconds(part));
            }
            if (sum.compareTo(seconds(totals[i])) != 0 || sum.compareTo(seconds(wrong[i])) == 0) {
                throw new IllegalStateException("the totals of " + List.of(durations[i]));
            }
            String parts = String.join(", ", List.of(durations[i]).subList(0, durations[i].length - 1)) + " and "
                    + durations[i][durations[i].length - 1];
            String lead = i % 2 == 0 ? "It took " : "The run took ";
            add(lines, "unit", CLOCK, "UTC", "CONSISTENT", lead + parts + ", " + totals[i] + " in total.");
            add(lines, "unit", CLOCK, "UTC", "INCONSISTENT", lead + parts + ", a total of " + wrong[i] + ".");
        }
        // data sizes: KB to TB read two ways, KiB to TiB and B one way
        String[][] data = {{"2 GB", "3 GB", "5 GB"}, {"2 GB", "3 GB", "6 GB"}, {"512 MiB", "512 MiB", "1 GiB"},
            {"1 GiB", "1 GiB", "3 GiB"}, {"1 KB", "1000 B", "2 KB"}, {"1 GB", "500 MB", "1.5 GB"},
            {"1 GB", "1 GB", "3 GB"}, {"256 KiB", "768 KiB", "1 MiB"}, {"1 TB", "1 TB", "2 TB"},
            {"1 MB", "24 KB", "1 MB"}};
        for (String[] d : data) {
            List<Boolean> readings = new ArrayList<>();
            boolean twoWays = List.of(d).stream().anyMatch(q -> q.matches(".* [KMGT]B"));
            for (boolean binary : twoWays ? List.of(false, true) : List.of(false)) {
                BigDecimal sum = bytes(d[0], binary).add(bytes(d[1], binary));
                readings.add(sum.compareTo(bytes(d[2], binary)) == 0);
            }
            String expected = readings.stream().allMatch(r -> r) ? "CONSISTENT"
                    : readings.stream().noneMatch(r -> r) ? "INCONSISTENT" : "UNVERIFIABLE";
            add(lines, "unit", CLOCK, "UTC", expected, "We used " + d[0] + " and " + d[1] + ", " + d[2] + " in total.");
        }
        add(lines, "unit", CLOCK, "UTC", "CONSISTENT", "It cost 5 USD and 3 USD, 8 USD in total.");
        add(lines, "unit", CLOCK, "UTC", "INCONSISTENT", "It cost 5 USD and 3 USD, 9 USD in total.");
        add(lines, "unit", CLOCK, "UTC", "CONSISTENT", "It cost 2.50 EUR and 1.25 EUR, 3.75 EUR in total.");
        add(lines, "unit", CLOCK, "UTC", "INCONSISTENT", "It cost 2.50 EUR and 1.25 EUR, a total of 4 EUR.");
        add(lines, "unit", CLOCK, "UTC", "UNVERIFIABLE", "The trip took 2 months and 1 month, 3 months in total.");
        add(lines, "unit", CLOCK, "UTC", "UNVERIFIABLE", "The plan took 1 year and 2 weeks, 54 weeks in total.");
        add(lines, "unit", CLOCK, "UTC", "CONSISTENT",
                "We downloaded 2 GB, waited 3 minutes and downloaded 1 GB, 3 GB in total.");
        add(lines, "unit", CLOCK, "UTC", "INCONSISTENT",
                "We waited 2 minutes, downloaded 3 GB and waited 1 minute, 6 minutes in total.");
        add(lines, "unit", CLOCK, "UTC", "NONE", "It took 2 minutes, 2 minutes in total.");
        add(lines, "unit", CLOCK, "UTC", "NONE", "It cost 5 USD and 3 EUR, 8 USD in total.");
    }

    /** The lines of G3, in a fixed order: dates, arithmetic, counts, versions, totals and units. */
    static List<String> generate() {
        List<String> lines = new ArrayList<>();
        dates(lines);
        arithmetic(lines);
        counts(lines);
        versions(lines);
        units(lines);
        return List.copyOf(lines);
    }

    /** Prints the lines of G3, one a line. */
    public static void main(String[] args) {
        generate().forEach(System.out::println);
    }
}
