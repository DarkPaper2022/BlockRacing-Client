package top.lqsnow.blockracing.client;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class BoardStateTest {
    @Test void readsActualPaperEncoderFixtureWhenProvided() throws Exception {
        String fixture = System.getProperty("blockracing.fixture", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(fixture.isEmpty(), "Optional parent-plugin contract fixture");
        var state = BoardState.decode(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(fixture)));
        assertEquals("red", state.team());
        assertEquals("用望远镜观察 20 种不同生物", state.tasks().getFirst().title());
        assertEquals("minecraft:spyglass", state.tasks().getFirst().icon());
        assertEquals("", state.tasks().getFirst().model());
        assertEquals(14, state.tasks().getFirst().current());
        assertEquals(20, state.tasks().getFirst().required());
    }

    private byte[] gzip(String json) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(json.getBytes(StandardCharsets.UTF_8)); }
        return bytes.toByteArray();
    }
    private String snapshot(String tasks) {
        return "{\"version\":1,\"team\":\"red\",\"state\":\"INGAME\",\"chinese\":true,\"score\":3,\"winScore\":70,\"totalScore\":140,\"tasks\":[" + tasks + "]}";
    }
    private String task() {
        return "{\"id\":\"SPY\",\"index\":1,\"title\":\"观察20种生物\",\"icon\":\"minecraft:spyglass\",\"score\":3,\"status\":\"active\",\"current\":8,\"required\":20,\"progressKnown\":true}";
    }
    @Test void readsUtf8ServerSnapshotAndKeepsExactCounts() throws Exception {
        var state = BoardState.decode(gzip(snapshot(task())));
        assertEquals("观察20种生物", state.tasks().getFirst().title());
        assertEquals(0.4, state.tasks().getFirst().fraction());
        assertEquals(70, state.winScore());
    }
    @Test void rejectsProtocolMismatchAndDuplicateIds() throws Exception {
        assertThrows(IOException.class, () -> BoardState.decode(gzip(snapshot(task()).replace("\"version\":1", "\"version\":2"))));
        assertThrows(IOException.class, () -> BoardState.decode(gzip(snapshot(task() + "," + task()))));
    }
    @Test void rejectsZipBombMalformedAndOversizedPayloads() throws Exception {
        assertThrows(IOException.class, () -> BoardState.decode(gzip("x".repeat(BoardState.MAX_JSON + 1))));
        assertThrows(IOException.class, () -> BoardState.decode(new byte[BoardState.MAX_PACKET + 1]));
        assertThrows(IOException.class, () -> BoardState.decode(gzip("not json")));
    }
    @Test void rejectsZeroDenominatorAndNegativeProgress() throws Exception {
        assertThrows(IOException.class, () -> BoardState.decode(gzip(snapshot(task().replace("\"required\":20", "\"required\":0")))));
        assertThrows(IOException.class, () -> BoardState.decode(gzip(snapshot(task().replace("\"current\":8", "\"current\":-1")))));
    }
    @Test void boundedErrorsDoNotNeedSuccessFields() throws Exception {
        var state = BoardState.decode(gzip("{\"version\":1,\"error\":\"暂不可用\",\"tasks\":[]}"));
        assertEquals("暂不可用", state.error());
        assertTrue(state.tasks().isEmpty());
    }
}
