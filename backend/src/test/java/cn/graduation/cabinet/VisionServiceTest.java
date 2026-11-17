package cn.graduation.cabinet;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VisionServiceTest {
    @Test void takingAndReturningColaCancelsOut() throws Exception {
        var frames = new ObjectMapper().readTree("""
            {"frames":[{"index":1,"items":[]},{"index":2,"items":[{"name":"cola","quantity":1}]},
            {"index":3,"items":[{"name":"cola","quantity":1}]},{"index":4,"items":[]}]}
            """);
        var result = VisionService.netFromFrames(frames, 4, List.of("cola"));
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.items()).isEmpty();
    }

    @Test void missingReturnFrameCannotBecomeAnAutomaticCharge() throws Exception {
        var frames = new ObjectMapper().readTree("""
            {"frames":[{"index":1,"items":[]},{"index":2,"items":[{"name":"cola","quantity":1}]},
            {"index":3,"items":[{"name":"cola","quantity":1}]}]}
            """);
        assertThat(VisionService.netFromFrames(frames, 4, List.of("cola")).status()).isEqualTo("UNCERTAIN");
    }
    @Test void wrappedResponseStillRecognizesOneCola() throws Exception {
        String response = """
            <think>draft {invalid}</think>
            Summary {ignored}
            ```json
            {"frames":[{"index":1,"items":[]},{"index":2,"items":[{"name":"cola","quantity":1}]}]}
            ```
            """;
        var frames = VisionService.readModelOutput(new ObjectMapper(), response);
        var result = VisionService.netFromFrames(frames, 2, List.of("cola"));
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.items()).containsExactly(new PerceptionModels.VisionItem("cola", 1));
    }

    @Test void trailingCommaDoesNotHideRecognizedCola() throws Exception {
        var frames = VisionService.readModelOutput(new ObjectMapper(), """
            {"frames":[{"index":1,"items":[]},{"index":2,"items":[{"name":"cola","quantity":1},]},]}
            """);
        var result = VisionService.netFromFrames(frames, 2, List.of("cola"));
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.items()).containsExactly(new PerceptionModels.VisionItem("cola", 1));
    }

    @Test void numberedFramesPreserveReturnAndRejectMissingFrame() {
        var json = new ObjectMapper();
        var names = List.of("cola");
        var frames = VisionService.readNumberedFrames(json, "1:-\n2:1=1\n3:1=1\n4:-", 4, names);
        var result = VisionService.netFromFrames(frames, 4, names);
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.items()).isEmpty();
        assertThat(VisionService.readNumberedFrames(json, "1:-\n2:1=1\n4:-", 4, names)).isNull();
    }

}