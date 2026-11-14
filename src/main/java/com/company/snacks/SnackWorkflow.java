package com.company.snacks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory reference implementation. In production, synchronized critical sections
 * become database transactions with row locks / unique constraints.
 */
public class SnackWorkflow {
    public enum Office {
        OSLO("NOK", new BigDecimal("6000")),
        BENGALURU("INR", new BigDecimal("15000"));
        final String currency;
        final BigDecimal monthlyBudget;
        Office(String currency, BigDecimal monthlyBudget) {
            this.currency = currency; this.monthlyBudget = monthlyBudget;
        }
    }
    public enum RefillStatus { OPEN, ASSIGNED, ACCEPTED, PURCHASED, CLAIM_SUBMITTED, REIMBURSED, CANCELLED }
    public enum HrStatus { PENDING, APPROVED, REJECTED, PAID }

    public record Volunteer(String employeeId, String name, Office homeOffice,
                            Office temporaryOffice, boolean available, boolean onLeave) {
        public Office effectiveOffice() { return temporaryOffice == null ? homeOffice : temporaryOffice; }
        public boolean eligible(Office office) {
            return available && !onLeave && effectiveOffice() == office;
        }
    }
    public record RefillView(long id, Office office, String snack, RefillStatus status,
                             String assignee, BigDecimal reservedAmount, BigDecimal actualAmount,
                             YearMonth month, String hrClaimId) {}
    public record MonthlyReport(YearMonth month, Map<Office, BigDecimal> spendByOffice,
                                BigDecimal totalInNok, BigDecimal totalInInr) {}

    private static final class Refill {
        long id; Office office; String snack; YearMonth month; RefillStatus status = RefillStatus.OPEN;
        String assignee; BigDecimal reserved = BigDecimal.ZERO; BigDecimal actual = BigDecimal.ZERO;
        String receipt; String hrClaimId; Instant assignedAt;
        Refill(long id, Office office, String snack, YearMonth month) {
            this.id=id; this.office=office; this.snack=snack; this.month=month;
        }
    }
    private static final class Spend {
        BigDecimal purchased = BigDecimal.ZERO;
        BigDecimal reserved = BigDecimal.ZERO;
    }

    public interface ChatPort {
        void send(String employeeId, String message);
    }
    public interface HrPort {
        String submit(String employeeId, BigDecimal amount, String currency, String description, String receipt);
    }
    public static class FakeChat implements ChatPort {
        public final List<String> messages = new ArrayList<>();
        public void send(String employeeId, String message) { messages.add(employeeId + ": " + message); }
    }
    public static class FakeHr implements HrPort {
        public record Claim(String employeeId, BigDecimal amount, String currency, String description,
                            String receipt, HrStatus status) {}
        private final Map<String, Claim> claims = new LinkedHashMap<>();
        private final AtomicLong ids = new AtomicLong();
        public String submit(String employeeId, BigDecimal amount, String currency, String description, String receipt) {
            String id = "HR-" + ids.incrementAndGet();
            claims.put(id, new Claim(employeeId, amount, currency, description, receipt, HrStatus.PENDING));
            return id;
        }
        public Claim get(String id) { return claims.get(id); }
        public int size() { return claims.size(); }
    }

    private final Map<String, Volunteer> volunteers = new LinkedHashMap<>();
    private final Map<Long, Refill> refills = new LinkedHashMap<>();
    private final Map<String, Spend> spend = new HashMap<>();
    private final Map<Office, String> lastAssigned = new EnumMap<>(Office.class);
    private final AtomicLong ids = new AtomicLong();
    private final ChatPort chat;
    private final HrPort hr;
    private final Clock clock;
    private final Duration assignmentTtl;

    public SnackWorkflow(ChatPort chat, HrPort hr, Clock clock, Duration assignmentTtl) {
        this.chat=chat; this.hr=hr; this.clock=clock; this.assignmentTtl=assignmentTtl;
    }
    public synchronized void addVolunteer(Volunteer v) { volunteers.put(v.employeeId(), v); }

    public synchronized long reportEmpty(Office office, String snack) {
        long id=ids.incrementAndGet();
        YearMonth month=YearMonth.from(LocalDate.now(clock));
        refills.put(id, new Refill(id, office, snack, month));
        assignNext(refills.get(id));
        return id;
    }

    // A volunteer who is physically visiting another office is eligible there via temporaryOffice.
    // Least recently assigned eligible volunteer wins; ties are stable by employee ID.
    private void assignNext(Refill r) {
        List<Volunteer> candidates = volunteers.values().stream()
            .filter(v -> v.eligible(r.office))
            .sorted(Comparator.comparing((Volunteer v) -> Objects.equals(lastAssigned.get(r.office), v.employeeId()))
                .thenComparing(v -> v.employeeId()))
            .toList();
        if (candidates.isEmpty()) {
            r.status=RefillStatus.OPEN;
            chat.send("office-" + r.office, "Refill " + r.id + " needs a volunteer for " + r.snack);
            return;
        }
        Volunteer chosen=candidates.get(0);
        r.assignee=chosen.employeeId(); r.status=RefillStatus.ASSIGNED;
        r.assignedAt=clock.instant(); lastAssigned.put(r.office, chosen.employeeId());
        chat.send(chosen.employeeId(), "Please refill " + r.snack + " (refill #" + r.id +
            "). Accept with a planned spend within the remaining monthly budget.");
    }

    /** Reassign an expired assignment. Safe to call periodically from a scheduler. */
    public synchronized void reassignExpired() {
        Instant now=clock.instant();
        for (Refill r : refills.values()) {
            if ((r.status == RefillStatus.ASSIGNED || r.status == RefillStatus.ACCEPTED) &&
                r.assignedAt.plus(assignmentTtl).compareTo(now) <= 0) {
                chat.send(r.assignee, "Assignment #" + r.id + " expired; it has been released.");
                if (r.reserved.signum() > 0) {
                    Spend ledger=spend.get(key(r.office,r.month));
                    ledger.reserved=ledger.reserved.subtract(r.reserved);
                }
                r.assignee=null; r.status=RefillStatus.OPEN; r.reserved=BigDecimal.ZERO;
                assignNext(r);
            }
        }
    }

    /** Atomically accepts one assignment and reserves the volunteer's declared purchase cap. */
    public synchronized boolean accept(long refillId, String employeeId, BigDecimal plannedSpend) {
        Refill r=require(refillId);
        if (r.status != RefillStatus.ASSIGNED || !Objects.equals(r.assignee, employeeId)) return false;
        if (plannedSpend == null || plannedSpend.signum() <= 0) throw new IllegalArgumentException("Planned spend must be positive");
        if (plannedSpend.compareTo(remaining(r.office, r.month)) > 0) return false;
        r.reserved=money(plannedSpend);
        r.status=RefillStatus.ACCEPTED;
        spend.computeIfAbsent(key(r.office,r.month), k -> new Spend()).reserved =
            spend.get(key(r.office,r.month)).reserved.add(r.reserved);
        chat.send(employeeId, "Refill #" + r.id + " accepted. Purchase cap: " + r.reserved + " " + r.office.currency);
        return true;
    }

    /** Completes purchase and submits reimbursement. Actual spend cannot exceed reserved cap. */
    public synchronized String complete(long refillId, String employeeId, BigDecimal actualSpend, String receiptImageRef) {
        Refill r=require(refillId);
        if (r.status != RefillStatus.ACCEPTED || !Objects.equals(r.assignee, employeeId) ||
            r.reserved.signum() == 0) throw new IllegalStateException("Refill is not accepted by this volunteer");
        if (actualSpend == null || actualSpend.signum() <= 0) throw new IllegalArgumentException("Actual spend must be positive");
        actualSpend=money(actualSpend);
        if (actualSpend.compareTo(r.reserved) > 0) throw new IllegalArgumentException("Actual spend exceeds approved cap");
        if (receiptImageRef == null || receiptImageRef.isBlank()) throw new IllegalArgumentException("Receipt is required");

        Spend s=spend.get(key(r.office,r.month));
        s.reserved=s.reserved.subtract(r.reserved);
        s.purchased=s.purchased.add(actualSpend);
        r.actual=actualSpend; r.receipt=receiptImageRef; r.status=RefillStatus.PURCHASED;
        String claim=hr.submit(employeeId, actualSpend, r.office.currency,
            "Snack refill #" + r.id + " - " + r.snack + " - " + r.office, receiptImageRef);
        r.hrClaimId=claim; r.status=RefillStatus.CLAIM_SUBMITTED;
        chat.send(employeeId, "Receipt submitted to HR as " + claim + ". HR approval/payment is external.");
        return claim;
    }

    /** Called by HR webhook/polling adapter when claim status changes. */
    public synchronized void updateHrStatus(long refillId, HrStatus status) {
        Refill r=require(refillId);
        if (r.status != RefillStatus.CLAIM_SUBMITTED && r.status != RefillStatus.REIMBURSED)
            throw new IllegalStateException("No submitted claim for refill");
        if (status == HrStatus.PAID) r.status=RefillStatus.REIMBURSED;
        chat.send(r.assignee, "HR claim " + r.hrClaimId + " status: " + status);
    }

    public synchronized BigDecimal remaining(Office office, YearMonth month) {
        Spend s=spend.getOrDefault(key(office,month), new Spend());
        return office.monthlyBudget.subtract(s.purchased).subtract(s.reserved).max(BigDecimal.ZERO);
    }
    public synchronized RefillView get(long id) {
        Refill r=require(id);
        return new RefillView(r.id,r.office,r.snack,r.status,r.assignee,r.reserved,r.actual,r.month,r.hrClaimId);
    }
    public synchronized MonthlyReport report(YearMonth month, BigDecimal nokPerInr) {
        Map<Office,BigDecimal> totals=new EnumMap<>(Office.class);
        for (Office o:Office.values()) {
            Spend s=spend.getOrDefault(key(o,month),new Spend());
            totals.put(o,s.purchased.setScale(2,RoundingMode.HALF_UP));
        }
        // Exchange rate is explicitly supplied/configured by finance; NOK per 1 INR.
        BigDecimal nok=totals.get(Office.OSLO).add(totals.get(Office.BENGALURU).multiply(nokPerInr));
        BigDecimal inr=totals.get(Office.BENGALURU).add(totals.get(Office.OSLO).divide(nokPerInr,2,RoundingMode.HALF_UP));
        return new MonthlyReport(month,Collections.unmodifiableMap(totals),money(nok),money(inr));
    }
    private Refill require(long id) {
        Refill r=refills.get(id); if (r==null) throw new NoSuchElementException("Unknown refill " + id); return r;
    }
    private static String key(Office o, YearMonth m) { return o.name()+":"+m; }
    private static BigDecimal money(BigDecimal v) { return v.setScale(2,RoundingMode.HALF_UP); }
}
