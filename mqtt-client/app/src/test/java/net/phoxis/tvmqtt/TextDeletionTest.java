package net.phoxis.tvmqtt;
import org.junit.Test;
import static org.junit.Assert.*;

public class TextDeletionTest {
    @Test public void deletesBeforeCursorWithoutChangingSuffix() {
        TextDeletion result=TextDeletion.backspace("abcd",2,2);
        assertEquals("acd",result.text);assertEquals(1,result.cursor);
    }
    @Test public void removesSelectionInEitherDirection() {
        assertEquals("ad",TextDeletion.backspace("abcd",1,3).text);
        assertEquals("ad",TextDeletion.backspace("abcd",3,1).text);
    }
    @Test public void emptyOrBeginningIsNoOp() {
        assertEquals("",TextDeletion.backspace("",0,0).text);
        assertEquals("abc",TextDeletion.backspace("abc",0,0).text);
    }
    @Test public void deletesWholeSurrogatePair() {
        TextDeletion result=TextDeletion.backspace("a\ud83d\ude00b",3,3);
        assertEquals("ab",result.text);assertEquals(1,result.cursor);
    }
    @Test public void missingSelectionDefaultsToEnd() {
        assertEquals("ab",TextDeletion.backspace("abc",-1,-1).text);
    }
    @Test public void batchRemovesSelectionThenPrecedingCodePoints() {
        assertEquals("af",TextDeletion.backspace("abcdef",3,5,3).text);
        assertEquals("b",TextDeletion.backspace("a\ud83d\ude00b",3,3,2).text);
        assertEquals("",TextDeletion.backspace("abc",3,3,240).text);
    }
}
