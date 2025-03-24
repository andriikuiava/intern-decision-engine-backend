package ee.taltech.inbankbackend.config;

import java.util.Set;

/**
 * Holds all necessary constants for the decision engine.
 */
public class DecisionEngineConstants {
    public static final Integer MINIMUM_LOAN_AMOUNT = 2000;
    public static final Integer MAXIMUM_LOAN_AMOUNT = 10000;
    public static final Integer MAXIMUM_LOAN_PERIOD = 48;
    public static final Integer MINIMUM_LOAN_PERIOD = 12;
    public static final Integer SEGMENT_1_CREDIT_MODIFIER = 100;
    public static final Integer SEGMENT_2_CREDIT_MODIFIER = 300;
    public static final Integer SEGMENT_3_CREDIT_MODIFIER = 1000;

    public static final int MINIMUM_AGE = 18;
    public static final int DEFAULT_EXPECTED_LIFETIME = 82;
    public static final int ESTONIA_EXPECTED_LIFETIME = 78;
    public static final int LATVIA_EXPECTED_LIFETIME = 75;
    public static final int LITHUANIA_EXPECTED_LIFETIME = 76;

    public static final Set<String> VALID_COUNTRIES = Set.of("Estonia", "Latvia", "Lithuania");
}
