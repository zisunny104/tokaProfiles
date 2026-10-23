package dev.toka.profiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class NotesTest {
    @Test public void anEntryHasATypeFieldsAndAText() {
        var entries = Notes.entries("donation amount=500 id=a1 # 贊助 500");
        assertEquals(1, entries.size());
        assertEquals("donation", entries.get(0).type());
        assertEquals("500", entries.get(0).fields().get("amount"));
        assertEquals("贊助 500", entries.get(0).text());
    }

    @Test public void plainTextLinesAreOnlyForPeople() {
        assertTrue(Notes.entries("在群組裡很熱心\n認識管理員").isEmpty());
        assertEquals(1, Notes.entries("在群組裡很熱心\ndonation amount=1 id=x").size());
    }

    @Test public void aLineWithABrokenFieldIsNotAnEntry() {
        assertTrue(Notes.entries("donation amount 500").isEmpty());
        assertTrue(Notes.entries("Donation amount=500").isEmpty());
    }

    @Test public void anEntryWrittenBackReadsTheSame() {
        var entry = Notes.entries("donation amount=500 id=a1 paid=legacy # 舊資料").get(0);
        assertEquals(entry, Notes.parse(entry.line()));
    }

    @Test public void appendKeepsWhatIsThere() {
        assertEquals("a\nb", Notes.append("a", "b"));
        assertEquals("b", Notes.append("", "b"));
    }

    @Test public void theOldDonationTextBecomesAnEntryThatIsAlreadyPaid() {
        var entry = Notes.entries(Notes.modernized("贊助金額 300（舊資料）")).get(0);
        assertEquals("300", entry.fields().get("amount"));
        assertEquals("legacy", entry.fields().get("paid"));
        assertEquals("其他備註", Notes.modernized("其他備註"));
    }
}
