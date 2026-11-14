package com.company.snacks;

import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class SnackWorkflowTest {
    private SnackWorkflow.FakeChat chat;
    private SnackWorkflow.FakeHr hr;
    private SnackWorkflow workflow;
    private Clock clock;
    private final YearMonth month = YearMonth.of(2026, 11);

    @BeforeEach void setup() {
        clock=Clock.fixed(Instant.parse("2026-11-14T08:00:00Z"), ZoneOffset.UTC);
        chat=new SnackWorkflow.FakeChat(); hr=new SnackWorkflow.FakeHr();
        workflow=new SnackWorkflow(chat,hr,clock,Duration.ofHours(24));
        workflow.addVolunteer(new SnackWorkflow.Volunteer("o1","Olav",SnackWorkflow.Office.OSLO,null,true,false));
        workflow.addVolunteer(new SnackWorkflow.Volunteer("o2","Oda",SnackWorkflow.Office.OSLO,null,true,false));
        workflow.addVolunteer(new SnackWorkflow.Volunteer("b1","Bengaluru visitor",SnackWorkflow.Office.OSLO,
            SnackWorkflow.Office.BENGALURU,true,false));
    }

    @Test void onlyAssignedVolunteerCanAcceptAndComplete() {
        long id=workflow.reportEmpty(SnackWorkflow.Office.OSLO,"Coffee");
        var assigned=workflow.get(id).assignee();
        String other=assigned.equals("o1") ? "o2" : "o1";
        assertFalse(workflow.accept(id,other,new BigDecimal("300")));
        assertTrue(workflow.accept(id,assigned,new BigDecimal("500")));
        assertFalse(workflow.accept(id,assigned,new BigDecimal("500"))); // duplicate commit
        assertThrows(IllegalStateException.class,()->workflow.complete(id,other,new BigDecimal("250"),"receipt.png"));
        String claim=workflow.complete(id,assigned,new BigDecimal("450"),"receipt.png");
        assertNotNull(claim);
        assertEquals(1,hr.size());
        assertEquals(SnackWorkflow.RefillStatus.CLAIM_SUBMITTED,workflow.get(id).status());
    }

    @Test void incompleteAssignmentExpiresAndIsHandedToSomeoneElse() {
        long id=workflow.reportEmpty(SnackWorkflow.Office.OSLO,"Tea");
        String first=workflow.get(id).assignee();
        clock = Clock.offset(clock,Duration.ofHours(25));
        // Workflow receives a fixed Clock; create a second instance with mutable clock for time travel.
        MutableClock mutable=new MutableClock(Instant.parse("2026-11-14T08:00:00Z"),ZoneOffset.UTC);
        var c=new SnackWorkflow.FakeChat(); var h=new SnackWorkflow.FakeHr();
        var w=new SnackWorkflow(c,h,mutable,Duration.ofHours(24));
        w.addVolunteer(new SnackWorkflow.Volunteer("o1","Olav",SnackWorkflow.Office.OSLO,null,true,false));
        w.addVolunteer(new SnackWorkflow.Volunteer("o2","Oda",SnackWorkflow.Office.OSLO,null,true,false));
        long rid=w.reportEmpty(SnackWorkflow.Office.OSLO,"Tea");
        String original=w.get(rid).assignee();
        mutable.advance(Duration.ofHours(25));
        w.reassignExpired();
        assertNotEquals(original,w.get(rid).assignee());
        assertEquals(SnackWorkflow.RefillStatus.ASSIGNED,w.get(rid).status());
    }

    @Test void budgetReservationsPreventOverspendAndActualsAreCounted() {
        long id=workflow.reportEmpty(SnackWorkflow.Office.OSLO,"Snacks");
        String who=workflow.get(id).assignee();
        assertTrue(workflow.accept(id,who,new BigDecimal("5900")));
        long second=workflow.reportEmpty(SnackWorkflow.Office.OSLO,"Fruit");
        String secondWho=workflow.get(second).assignee();
        assertFalse(workflow.accept(second,secondWho,new BigDecimal("200")));
        assertEquals(new BigDecimal("100.00"),workflow.remaining(SnackWorkflow.Office.OSLO,month));
        workflow.complete(id,who,new BigDecimal("5700"),"receipt-1.png");
        assertEquals(new BigDecimal("300.00"),workflow.remaining(SnackWorkflow.Office.OSLO,month));
    }

    @Test void budgetsAndMonthlyTotalsStaySeparateAcrossCurrencies() {
        long oslo=workflow.reportEmpty(SnackWorkflow.Office.OSLO,"Coffee");
        String o=workflow.get(oslo).assignee();
        workflow.accept(oslo,o,new BigDecimal("1000"));
        workflow.complete(oslo,o,new BigDecimal("900"),"oslo.png");

        long blr=workflow.reportEmpty(SnackWorkflow.Office.BENGALURU,"Biscuits");
        String b=workflow.get(blr).assignee();
        assertEquals("b1",b); // employee temporarily visiting Bengaluru
        workflow.accept(blr,b,new BigDecimal("2000"));
        workflow.complete(blr,b,new BigDecimal("1800"),"blr.png");

        var report=workflow.report(month,new BigDecimal("0.80"));
        assertEquals(new BigDecimal("900.00"),report.spendByOffice().get(SnackWorkflow.Office.OSLO));
        assertEquals(new BigDecimal("1800.00"),report.spendByOffice().get(SnackWorkflow.Office.BENGALURU));
        assertEquals(new BigDecimal("2340.00"),report.totalInNok());
        assertEquals(new BigDecimal("2925.00"),report.totalInInr());
    }

    @Test void receiptIsMandatoryAndActualCannotExceedReservedCap() {
        long id=workflow.reportEmpty(SnackWorkflow.Office.OSLO,"Milk");
        String who=workflow.get(id).assignee();
        workflow.accept(id,who,new BigDecimal("500"));
        assertThrows(IllegalArgumentException.class,()->workflow.complete(id,who,new BigDecimal("501"),"r.png"));
        assertThrows(IllegalArgumentException.class,()->workflow.complete(id,who,new BigDecimal("400")," "));
    }

    static class MutableClock extends Clock {
        private Instant instant; private final ZoneId zone;
        MutableClock(Instant instant, ZoneId zone){this.instant=instant;this.zone=zone;}
        void advance(Duration d){instant=instant.plus(d);}
        public ZoneId getZone(){return zone;}
        public Clock withZone(ZoneId z){return new MutableClock(instant,z);}
        public Instant instant(){return instant;}
    }
}
