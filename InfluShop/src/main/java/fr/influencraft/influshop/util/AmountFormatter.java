package fr.influencraft.influshop.util;

public final class AmountFormatter {

    private AmountFormatter() {
    }

    /**
     * Groups thousands with a plain ASCII space (e.g. "12 500"). Built manually rather than
     * via NumberFormat/Locale.FRANCE, whose thousands separator is a narrow no-break space
     * (U+202F) that can render as a stray box in some Minecraft fonts.
     */
    public static String grouped(long amount) {
        String digits = Long.toString(Math.abs(amount));
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = digits.length() - 1; i >= 0; i--) {
            sb.append(digits.charAt(i));
            count++;
            if (count % 3 == 0 && i != 0) {
                sb.append(' ');
            }
        }
        String result = sb.reverse().toString();
        return amount < 0 ? "-" + result : result;
    }
}
