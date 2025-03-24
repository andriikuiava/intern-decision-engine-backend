package ee.taltech.inbankbackend.service;

import com.github.vladislavgoltjajev.personalcode.locale.estonia.EstonianPersonalCodeValidator;
import ee.taltech.inbankbackend.config.DecisionEngineConstants;
import ee.taltech.inbankbackend.exceptions.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Period;

/**
 * A service class that provides a method for calculating an approved loan amount and period for a customer.
 * The loan amount is calculated based on the customer's credit modifier,
 * which is determined by the last four digits of their ID code.
 */
@Service
public class DecisionEngine {

    private final EstonianPersonalCodeValidator validator = new EstonianPersonalCodeValidator();

    /**
     * Calculates the maximum loan amount and period for the customer based on their ID code,
     * the requested loan amount and the loan period.
     * The loan period must be between 12 and 48 months (inclusive).
     * The loan amount must be between 2000 and 10000€ (inclusive).
     *
     * @param personalCode    ID code of the customer that made the request.
     * @param requestedAmount Requested loan amount
     * @param requestedPeriod Requested loan period
     * @param country         The country for the loan application
     * @return A Decision object containing the approved loan amount and period, and an error message (if any)
     * @throws InvalidPersonalCodeException If the provided personal ID code is invalid
     * @throws InvalidLoanAmountException   If the requested loan amount is invalid
     * @throws InvalidLoanPeriodException   If the requested loan period is invalid
     * @throws NoValidLoanException         If there is no valid loan found for the given ID code, loan amount and loan period
     */
    public Decision calculateApprovedLoan(String personalCode, Long requestedAmount, int requestedPeriod, String country)
            throws InvalidPersonalCodeException, InvalidLoanAmountException, InvalidLoanPeriodException,
            NoValidLoanException, InvalidAgeException, InvalidCountryException {

        // Verify inputs
        verifyInputs(personalCode, requestedAmount, requestedPeriod);

        // Get customer's credit modifier based on segment
        int creditModifier = getCreditModifier(personalCode);

        if (!DecisionEngineConstants.VALID_COUNTRIES.contains(country)) {
            throw new InvalidCountryException("Invalid country: " + country + ". Allowed countries are Estonia, Latvia, and Lithuania.");
        }

        // If customer has debt (credit modifier = 0), no loan is possible
        if (creditModifier == 0) {
            throw new NoValidLoanException("No valid loan found due to existing debt!");
        }

        //check age restrictions
        checkAgeRestrictions(personalCode, requestedPeriod, country);

        // Try to find the maximum approved loan amount for the requested period
        Integer approvedAmount = findMaxApprovedAmount(creditModifier, requestedPeriod);

        // If no valid amount is found for the requested period, try to find a new period
        if (approvedAmount == null) {
            int newPeriod = findAlternativePeriod(creditModifier, requestedPeriod);
            if (newPeriod > 0) {
                approvedAmount = findMaxApprovedAmount(creditModifier, newPeriod);
                return new Decision(approvedAmount, newPeriod, null);
            } else {
                throw new NoValidLoanException("No valid loan found!");
            }
        }

        return new Decision(approvedAmount, requestedPeriod, null);
    }

    /**
     * Finds the maximum loan amount that would be approved for the given credit modifier and period.
     * Implements the scoring algorithm: credit score = ((credit modifier / loan amount) * loan period) / 10
     * A score >= 0.1 is considered approved.
     *
     * @param creditModifier The customer's credit modifier
     * @param loanPeriod     The loan period in months
     * @return The maximum approved amount, or null if no valid amount exists
     */
    private Integer findMaxApprovedAmount(int creditModifier, int loanPeriod) {
        // Start from the maximum possible amount and go down
        for (int amount = DecisionEngineConstants.MAXIMUM_LOAN_AMOUNT;
             amount >= DecisionEngineConstants.MINIMUM_LOAN_AMOUNT;
             amount -= 100) {

            double creditScore = ((double) creditModifier / amount * loanPeriod) / 10;

            if (creditScore >= 0.1) {
                return amount;
            }
        }

        return null; // No valid amount found
    }

    /**
     * Attempts to find an alternative loan period that would allow for a valid loan.
     * Searches from the requested period up to the maximum allowed period.
     *
     * @param creditModifier  The customer's credit modifier
     * @param requestedPeriod The initially requested loan period
     * @return A valid alternative period, or -1 if none is found
     */
    private int findAlternativePeriod(int creditModifier, int requestedPeriod) {
        for (int period = requestedPeriod + 1; period <= DecisionEngineConstants.MAXIMUM_LOAN_PERIOD; period++) {
            Integer approvedAmount = findMaxApprovedAmount(creditModifier, period);
            if (approvedAmount != null) {
                return period;
            }
        }
        return -1; // No alternative period found
    }

    private int getCreditModifier(String personalCode) {
        String lastFourDigits = personalCode.substring(personalCode.length() - 4);
        int id = Integer.parseInt(lastFourDigits);

        if (id <= 3000) {
            return DecisionEngineConstants.SEGMENT_1_CREDIT_MODIFIER;
        } else if (id <= 6000) {
            return DecisionEngineConstants.SEGMENT_2_CREDIT_MODIFIER;
        } else if (id <= 9999) {
            return DecisionEngineConstants.SEGMENT_3_CREDIT_MODIFIER;
        }

        return 0;
    }

    private void verifyInputs(String personalCode, Long loanAmount, int loanPeriod)
            throws InvalidPersonalCodeException, InvalidLoanAmountException, InvalidLoanPeriodException {
        if (!validator.isValid(personalCode)) {
            throw new InvalidPersonalCodeException("Invalid personal ID code!");
        }

        if (loanAmount < DecisionEngineConstants.MINIMUM_LOAN_AMOUNT ||
                loanAmount > DecisionEngineConstants.MAXIMUM_LOAN_AMOUNT) {
            throw new InvalidLoanAmountException(
                    "Invalid loan amount! Loan amount must be between " + DecisionEngineConstants.MINIMUM_LOAN_AMOUNT +
                            " and " + DecisionEngineConstants.MAXIMUM_LOAN_AMOUNT + "€");
        }

        if (loanPeriod < DecisionEngineConstants.MINIMUM_LOAN_PERIOD ||
                loanPeriod > DecisionEngineConstants.MAXIMUM_LOAN_PERIOD) {
            throw new InvalidLoanPeriodException(
                    "Invalid loan period! Loan period must be between " + DecisionEngineConstants.MINIMUM_LOAN_PERIOD +
                            " and " + DecisionEngineConstants.MAXIMUM_LOAN_PERIOD + " months");
        }
    }

    private void checkAgeRestrictions(String personalCode, int loanPeriod, String country) throws InvalidAgeException {
        // Extract birth date from personal code
        LocalDate birthDate = extractBirthDate(personalCode);
        LocalDate currentDate = LocalDate.now();
        int age = Period.between(birthDate, currentDate).getYears();

        if (age < DecisionEngineConstants.MINIMUM_AGE) {
            throw new InvalidAgeException("Customer is underage and cannot receive a loan.");
        }

        // Get life expectancy for the country
        int lifeExpectancy = getLifeExpectancy(country);

        int maxAcceptableAge = lifeExpectancy - (loanPeriod / 12);

        if (age > maxAcceptableAge) {
            throw new InvalidAgeException("Customer is too old to receive a loan for this period.");
        }
    }

    private LocalDate extractBirthDate(String personalCode) {
        int yearPrefix;
        int year = Integer.parseInt(personalCode.substring(1, 3));
        int month = Integer.parseInt(personalCode.substring(3, 5));
        int day = Integer.parseInt(personalCode.substring(5, 7));
        int genderDigit = Character.getNumericValue(personalCode.charAt(0));

        if (genderDigit == 3 || genderDigit == 4) {
            yearPrefix = 19;
        } else if (genderDigit == 5 || genderDigit == 6) {
            yearPrefix = 20;
        } else {
            yearPrefix = 18;
        }

        year = year + yearPrefix * 100;
        return LocalDate.of(year, month, day);
    }


    private int getLifeExpectancy(String country) {
        return switch (country) {
            case "Estonia" -> DecisionEngineConstants.ESTONIA_EXPECTED_LIFETIME;
            case "Latvia" -> DecisionEngineConstants.LATVIA_EXPECTED_LIFETIME;
            case "Lithuania" -> DecisionEngineConstants.LITHUANIA_EXPECTED_LIFETIME;
            default -> DecisionEngineConstants.DEFAULT_EXPECTED_LIFETIME;
        };
    }
}
