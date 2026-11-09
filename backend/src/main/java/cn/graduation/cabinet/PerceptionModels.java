package cn.graduation.cabinet;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

final class PerceptionModels {
    record Photo(@Positive long timestamp, @NotBlank @Size(max=450000) String dataUrl) {}
    record Report(@NotNull @Min(0) @Max(100000000) Integer beforeGrams,
                  @NotNull @Min(0) @Max(100000000) Integer afterGrams,
                  @NotNull @Size(max=20000) List<@NotBlank @Size(max=40) String> beforeTags,
                  @NotNull @Size(max=20000) List<@NotBlank @Size(max=40) String> afterTags,
                  @NotNull @Size(max=6) List<@NotNull @Valid Photo> photos) {}
    record VisionItem(String name, int quantity) {}
    record VisionResult(String status, List<VisionItem> items) {}
    record Line(@NotBlank @Size(max=40) String productId,@Min(1) @Max(1000) int quantity) {}
    record CancelReview(@NotBlank @Size(max=500) String note) {}
    record Review(@NotNull @Size(max=100) List<@NotNull @Valid Line> items,
                  @NotBlank @Size(max=500) String note) {}
}
