import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class TaxEstimator {

    public static void main(String[] args) {
        printHeader();
        Scanner sc = new Scanner(System.in);

        FilingStatus status = promptFilingStatus(sc);

        BigDecimal wages = promptMoney(sc, "W-2 wages (ordinary income): $");
        BigDecimal otherOrdinaryIncome = promptMoney(sc, "Other ordinary income (interest, side gigs, etc.): $");
        BigDecimal ltCapGains = promptMoney(sc, "Long-term capital gains/qualified dividends: $");

        BigDecimal hsa = promptMoney(sc, "HSA contributions (above-the-line): $");
        BigDecimal tradIra = promptMoney(sc, "Traditional IRA contributions (above-the-line): $");
        BigDecimal studentLoanInterest = promptMoney(sc, "Student loan interest paid (capped at $2,500): $");
        if (studentLoanInterest.compareTo(new BigDecimal("2500")) > 0) {
            studentLoanInterest = new BigDecimal("2500");
        }

        boolean useStandard = promptYesNo(sc, "Use STANDARD deduction? (y/n): ");
        BigDecimal itemized = BigDecimal.ZERO;
        if (!useStandard) {
            itemized = promptMoney(sc, "Enter ITEMIZED deductions total: $");
        }

        int qualifyingChildren = promptInt(sc, "Qualifying children under 17 (simplified CTC): ");

        BigDecimal fedWithheld = promptMoney(sc, "Federal tax withheld (from W-2/1099): $");
        BigDecimal stateRate = promptPercent(sc, "Optional state tax as flat rate (e.g., 5 for 5%). Enter 0 to skip: ");

        Estimate estimate = estimateTax(
                status,
                wages,
                otherOrdinaryIncome,
                ltCapGains,
                hsa,
                tradIra,
                studentLoanInterest,
                useStandard,
                itemized,
                qualifyingChildren,
                fedWithheld,
                stateRate
        );

        printEstimate(estimate);
        sc.close();
    }

    enum FilingStatus { SINGLE, MARRIED_FILING_JOINTLY }

    static class Estimate {
        FilingStatus status;
        BigDecimal agi;
        BigDecimal deductionUsed;
        BigDecimal taxableOrdinary;
        BigDecimal taxableLtcg;
        BigDecimal ordinaryTax;
        BigDecimal ltcgTax;
        BigDecimal credits;
        BigDecimal federalTax;
        BigDecimal stateTax;
        BigDecimal totalTaxLiability;
        BigDecimal withheld;
        BigDecimal refundOrOwed;
    }

    // Simplified: only two filing statuses with fewer brackets
    static final Map<FilingStatus, BigDecimal> STANDARD_DEDUCTION = Map.of(
            FilingStatus.SINGLE, bd(15000),
            FilingStatus.MARRIED_FILING_JOINTLY, bd(30000)
    );

    static final Map<FilingStatus, List<Bracket>> ORDINARY_BRACKETS = new EnumMap<>(FilingStatus.class);
    static {
        ORDINARY_BRACKETS.put(FilingStatus.SINGLE, List.of(
                b(11000, 0.10), b(44725, 0.12), b(Double.POSITIVE_INFINITY, 0.22)
        ));
        ORDINARY_BRACKETS.put(FilingStatus.MARRIED_FILING_JOINTLY, List.of(
                b(22000, 0.10), b(89450, 0.12), b(Double.POSITIVE_INFINITY, 0.22)
        ));
    }

    static final Map<FilingStatus, List<Bracket>> LTCG_BRACKETS = new EnumMap<>(FilingStatus.class);
    static {
        LTCG_BRACKETS.put(FilingStatus.SINGLE, List.of(
                b(44725, 0.00), b(Double.POSITIVE_INFINITY, 0.15)
        ));
        LTCG_BRACKETS.put(FilingStatus.MARRIED_FILING_JOINTLY, List.of(
                b(89450, 0.00), b(Double.POSITIVE_INFINITY, 0.15)
        ));
    }

    static class Bracket {
        BigDecimal cap;
        BigDecimal rate;
        Bracket(double cap, double rate) {
            this.cap = cap == Double.POSITIVE_INFINITY ? null : bd(cap);
            this.rate = bd(rate);
        }
    }

    static Estimate estimateTax(
            FilingStatus status,
            BigDecimal wages,
            BigDecimal otherOrdinary,
            BigDecimal ltCapGains,
            BigDecimal hsa,
            BigDecimal tradIra,
            BigDecimal studentLoanInterest,
            boolean useStandard,
            BigDecimal itemized,
            int qualifyingChildren,
            BigDecimal withheld,
            BigDecimal stateRatePercent
    ) {
        Estimate e = new Estimate();
        e.status = status;

        BigDecimal ordinaryIncome = n(wages).add(n(otherOrdinary));
        BigDecimal ltcgIncome = n(ltCapGains);
        BigDecimal adjustments = n(hsa).add(n(tradIra)).add(n(studentLoanInterest));

        e.agi = ordinaryIncome.add(ltcgIncome).subtract(adjustments).max(BigDecimal.ZERO);
        BigDecimal deduction = useStandard ? STANDARD_DEDUCTION.get(status) : n(itemized);
        e.deductionUsed = deduction;

        BigDecimal taxableIncome = e.agi.subtract(deduction).max(BigDecimal.ZERO);
        e.taxableLtcg = taxableIncome.min(ltcgIncome);
        e.taxableOrdinary = taxableIncome.subtract(e.taxableLtcg).max(BigDecimal.ZERO);

        e.ordinaryTax = applyBrackets(e.taxableOrdinary, ORDINARY_BRACKETS.get(status));
        e.ltcgTax = applyBrackets(e.taxableLtcg, LTCG_BRACKETS.get(status));

        e.credits = bd(2000).multiply(BigDecimal.valueOf(Math.max(0, qualifyingChildren)));
        BigDecimal grossFed = e.ordinaryTax.add(e.ltcgTax);
        e.federalTax = grossFed.subtract(e.credits).max(BigDecimal.ZERO);

        BigDecimal stateRate = n(stateRatePercent).divide(bd(100), 10, RoundingMode.HALF_UP);
        e.stateTax = e.agi.multiply(stateRate).setScale(2, RoundingMode.HALF_UP);

        e.totalTaxLiability = e.federalTax.add(e.stateTax);
        e.withheld = n(withheld);
        e.refundOrOwed = e.withheld.subtract(e.federalTax).setScale(2, RoundingMode.HALF_UP);

        return e;
    }

    static BigDecimal applyBrackets(BigDecimal amount, List<Bracket> brackets) {
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal prevCap = BigDecimal.ZERO;
        for (Bracket b : brackets) {
            BigDecimal layer;
            if (b.cap == null) {
                layer = amount.subtract(prevCap).max(BigDecimal.ZERO);
            } else {
                BigDecimal cap = b.cap.min(amount);
                layer = cap.subtract(prevCap).max(BigDecimal.ZERO);
                prevCap = b.cap;
            }
            if (layer.signum() > 0) tax = tax.add(layer.multiply(b.rate));
            if (b.cap != null && amount.compareTo(b.cap) <= 0) break;
        }
        return tax.setScale(2, RoundingMode.HALF_UP);
    }

    static void printHeader() {
        System.out.println("\n==============================");
        System.out.println("   Rough Tax Estimator (CLI)");
        System.out.println("==============================\n");
    }

    static FilingStatus promptFilingStatus(Scanner sc) {
        System.out.println("Choose filing status:");
        System.out.println("  1) Single");
        System.out.println("  2) Married filing jointly");
        while (true) {
            System.out.print("Enter 1-2: ");
            String s = sc.nextLine().trim();
            switch (s) {
                case "1": return FilingStatus.SINGLE;
                case "2": return FilingStatus.MARRIED_FILING_JOINTLY;
                default: System.out.println("Invalid selection. Try again.");
            }
        }
    }

    static BigDecimal promptMoney(Scanner sc, String label) {
        while (true) {
            System.out.print(label);
            String s = sc.nextLine().replace(",", "").trim();
            try {
                BigDecimal val = new BigDecimal(s);
                if (val.signum() < 0) val = BigDecimal.ZERO;
                return val.setScale(2, RoundingMode.HALF_UP);
            } catch (Exception e) {
                System.out.println("  Please enter a number (e.g., 12345.67).");
            }
        }
    }

    static int promptInt(Scanner sc, String label) {
        while (true) {
            System.out.print(label);
            String s = sc.nextLine().trim();
            try {
                int v = Integer.parseInt(s);
                return Math.max(0, v);
            } catch (Exception e) {
                System.out.println("  Please enter a whole number.");
            }
        }
    }

    static boolean promptYesNo(Scanner sc, String label) {
        while (true) {
            System.out.print(label);
            String s = sc.nextLine().trim().toLowerCase();
            if (s.startsWith("y")) return true;
            if (s.startsWith("n")) return false;
            System.out.println("  Please enter y or n.");
        }
    }

    static BigDecimal promptPercent(Scanner sc, String label) {
        while (true) {
            System.out.print(label);
            String s = sc.nextLine().replace("%", "").trim();
            try {
                BigDecimal val = new BigDecimal(s);
                if (val.signum() < 0) val = BigDecimal.ZERO;
                return val.setScale(3, RoundingMode.HALF_UP);
            } catch (Exception e) {
                System.out.println("  Please enter a percent like 5 or 5.25.");
            }
        }
    }

    static void printEstimate(Estimate e) {
        System.out.println("\n----- ESTIMATE SUMMARY -----");
        System.out.println("Filing status:            " + e.status);
        System.out.println("AGI:                      $" + fmt(e.agi));
        System.out.println("Deduction used:           $" + fmt(e.deductionUsed));
        System.out.println("Taxable ordinary income:  $" + fmt(e.taxableOrdinary));
        System.out.println("Taxable LT cap gains/QD:  $" + fmt(e.taxableLtcg));
        System.out.println("Ordinary income tax:      $" + fmt(e.ordinaryTax));
        System.out.println("LTCG/QD tax:              $" + fmt(e.ltcgTax));
        System.out.println("Credits (simplified):     $" + fmt(e.credits));
        System.out.println("Federal tax:              $" + fmt(e.federalTax));
        System.out.println("State tax (optional):     $" + fmt(e.stateTax));
        System.out.println("Total tax liability:      $" + fmt(e.totalTaxLiability));
        System.out.println("Federal tax withheld:     $" + fmt(e.withheld));
        System.out.println("----------------------------");
        BigDecimal diff = e.refundOrOwed;
        if (diff.signum() >= 0) {
            System.out.println("Estimated FEDERAL REFUND: $" + fmt(diff));
        } else {
            System.out.println("Estimated AMOUNT OWED:    $" + fmt(diff.abs()));
        }
        System.out.println("(Rough estimate only; real returns vary.)\n");
    }

    static BigDecimal bd(double v) { return new BigDecimal(String.valueOf(v)); }
    static BigDecimal n(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    static Bracket b(double cap, double rate) { return new Bracket(cap, rate); }
    static String fmt(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
}
