package cn.graduation.cabinet;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

/** record是Java 17的不可变数据载体；请求参数、感知数据和返回值使用不同类型。 */
public final class Models {
    private Models() {}
    public record Product(String id, String name, int priceCents, int weightGrams, int thresholdQty, boolean enabled) {}
    public record ProductInput(@NotBlank @Size(max=40) String id, @NotBlank @Size(max=100) String name,
        @Min(1) @Max(100000) int priceCents, @Min(1) @Max(100000) int weightGrams,
        @Min(0) @Max(10000) int thresholdQty, boolean enabled) {}
    public record Login(@NotBlank @Size(max=40) String role, @Size(max=80) String userId, String password) {}
    public record OpenRequest(@NotBlank String cabinetId) {}
    public record CabinetInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,40}") String id,
        @NotBlank @Size(max=100) String name, @NotBlank @Size(max=200) String location) {}
    public record StockRequest(@NotBlank String cabinetId, @NotBlank String productId, @Min(1) @Max(1000) int quantity) {}
    public record SimulateRequest(@NotBlank String productId, @Pattern(regexp="TAKE|RETURN") @NotNull String direction,
        @Min(1) @Max(10) int quantity, @Min(-500) @Max(500) int weightError,
        boolean rfidMiss, @Size(max=40) String rfidOverride, boolean visionDifferent) {}
    /** RFID的delta是模拟标签集合前后差得到的带符号数量：取出负，放回正。 */
    public record Signal(int weightDeltaGrams, Map<String,Integer> rfidDelta, boolean visionDifferent) {}
    public record Candidate(String productId, String name, int quantity, int residualGrams) {}
    public record FusionResult(String direction, String status, String productId, int quantity,
        List<Candidate> candidates, List<String> steps, String reason) {}
    public record ResolveRequest(@NotBlank String productId, @Min(1) @Max(10) int quantity,
        @NotNull @Pattern(regexp="TAKE|RETURN") String direction,
        @NotBlank @Size(max=500) String note) {}
    public record PaymentRequest(@NotBlank @Size(max=80) String requestKey, @NotNull Boolean success) {}
    public record OrderItem(String productId, String name, int quantity, int unitPriceCents, int subtotalCents) {}
    public record Principal(String userId, boolean admin) {}
}
