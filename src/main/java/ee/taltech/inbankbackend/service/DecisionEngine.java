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
     * @param personalCode ID code of the customer that made the request.
     * @param requestedAmount Requested loan amount
     * @param requestedPeriod Requested loan period
     * @return A Decision object containing the approved loan amount and period, and an error message (if any)
     * @throws InvalidPersonalCodeException If the provided personal ID code is invalid
     * @throws InvalidLoanAmountException If the requested loan amount is invalid
     * @throws InvalidLoanPeriodException If the requested loan period is invalid
     * @throws NoValidLoanException If there is no valid loan found for the given ID code, loan amount and loan period
     */
    public Decision calculateApprovedLoan(String personalCode, Long requestedAmount, int requestedPeriod)
            throws InvalidPersonalCodeException, InvalidLoanAmountException, InvalidLoanPeriodException,
            NoValidLoanException, InvalidAgeException {

        // Verify inputs
        verifyInputs(personalCode, requestedAmount, requestedPeriod);

        // Get customer's credit modifier based on segment
        int creditModifier = getCreditModifier(personalCode);

        // If customer has debt (credit modifier = 0), no loan is possible
        if (creditModifier == 0) {
            throw new NoValidLoanException("No valid loan found due to existing debt!");
        }

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
     * @param loanPeriod The loan period in months
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
     * @param creditModifier The customer's credit modifier
     * @param requestedPeriod The initially requested loan period
     * @return A valid alternative period, or -1 if none is found
     */
    private int findAlternativePeriod(int creditModifier, int requestedPeriod) {
        // Try increasing the period to find a valid loan
        for (int period = requestedPeriod + 1; period <= DecisionEngineConstants.MAXIMUM_LOAN_PERIOD; period++) {
            if (findMaxApprovedAmount(creditModifier, period) != null) {
                return period;
            }
        }

        return -1; // No valid period found
    }

    /**
     * Calculates the credit modifier of the customer according to the last four digits of their ID code.
     * Debt - 0000...2499
     * Segment 1 - 2500...4999
     * Segment 2 - 5000...7499
     * Segment 3 - 7500...9999
     *
     * @param personalCode ID code of the customer that made the request.
     * @return Credit modifier based on the customer's segment
     */
    private int getCreditModifier(String personalCode) {
        int segment = Integer.parseInt(personalCode.substring(personalCode.length() - 4));

        if (segment < 2500) {
            return 0; // Debt
        } else if (segment < 5000) {
            return DecisionEngineConstants.SEGMENT_1_CREDIT_MODIFIER;
        } else if (segment < 7500) {
            return DecisionEngineConstants.SEGMENT_2_CREDIT_MODIFIER;
        }

        return DecisionEngineConstants.SEGMENT_3_CREDIT_MODIFIER;
    }

    /**
     * Verify that all inputs are valid according to business rules.
     * If inputs are invalid, then throws corresponding exceptions.
     *
     * @param personalCode Provided personal ID code
     * @param requestedAmount Requested loan amount
     * @param requestedPeriod Requested loan period
     * @throws InvalidPersonalCodeException If the provided personal ID code is invalid
     * @throws InvalidLoanAmountException If the requested loan amount is invalid
     * @throws InvalidLoanPeriodException If the requested loan period is invalid
     */
    private void verifyInputs(String personalCode, Long requestedAmount, int requestedPeriod)
            throws InvalidPersonalCodeException, InvalidLoanAmountException, InvalidLoanPeriodException, InvalidAgeException {


        int age = calculateAgeFromPersonalCode(personalCode);
        int maxAllowedAge = DecisionEngineConstants.EXPECTED_LIFETIME -
                DecisionEngineConstants.MAXIMUM_LOAN_PERIOD_YEARS;

        if (age < DecisionEngineConstants.MINIMUM_AGE || age > maxAllowedAge) {
            throw new InvalidAgeException("Customer's age is outside the allowed range!");
        }

        if (!validator.isValid(personalCode)) {
            throw new InvalidPersonalCodeException("Invalid personal code!");
        }

        if (requestedAmount < DecisionEngineConstants.MINIMUM_LOAN_AMOUNT ||
                requestedAmount > DecisionEngineConstants.MAXIMUM_LOAN_AMOUNT) {
            throw new InvalidLoanAmountException("Invalid loan amount!");
        }

        if (requestedPeriod < DecisionEngineConstants.MINIMUM_LOAN_PERIOD ||
                requestedPeriod > DecisionEngineConstants.MAXIMUM_LOAN_PERIOD) {
            throw new InvalidLoanPeriodException("Invalid loan period!");
        }
    }

    private int calculateAgeFromPersonalCode(String personalCode) {
        int century = (personalCode.charAt(0) - '0') <= 2 ? 1800 :
                (personalCode.charAt(0) - '0') <= 4 ? 1900 : 2000;

        int birthYear = century + Integer.parseInt(personalCode.substring(1, 3));
        int birthMonth = Integer.parseInt(personalCode.substring(3, 5));
        int birthDay = Integer.parseInt(personalCode.substring(5, 7));

        LocalDate birthDate = LocalDate.of(birthYear, birthMonth, birthDay);
        LocalDate currentDate = LocalDate.now();

        return Period.between(birthDate, currentDate).getYears();
    }

}