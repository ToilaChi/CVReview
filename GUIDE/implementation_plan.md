# Implementation Plan: CV Upload Pipeline – Bug Fixes & SSE

## Tổng quan

Tổng cộng **5 nhóm thay đổi**, sắp xếp theo thứ tự ưu tiên từ cao đến thấp.
Mỗi nhóm độc lập nhau, có thể implement và test riêng biệt.

---

## Fix 1 — 504 Gateway Timeout (Ưu tiên: 🔴 Cao)

### Nguyên nhân
Upload 7 CVs lên Drive **tuần tự** trong `UploadCVService.uploadCVsByHR()` có thể mất
7 × 5s = ~35s. CircuitBreaker `defaultCircuitBreaker` timeout chỉ là **30s** → gateway kill request.

### Thay đổi

#### [MODIFY] `api-gateway/src/main/resources/application-local.yml`  (đã làm)
```yaml
# Tăng từ 30s → 120s (đủ cho 20 files × 5s/file upload Drive)
timelimiter:
  instances:
    defaultCircuitBreaker:
      timeout-duration: 120s
```

#### [MODIFY] `UploadCVService.uploadCVsByHR()` — song song hóa Drive upload

Thay vòng `for` tuần tự bằng `CompletableFuture.allOf()`:
```java
// Trước: tuần tự O(n)
for (MultipartFile file : files) {
    uploadSingleCV(file, ...);
}

// Sau: song song O(1) về thời gian chờ  
List<CompletableFuture<Void>> futures = files.stream()
    .map(file -> CompletableFuture.runAsync(() -> uploadSingleCV(file, ...)))
    .toList();
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

> [!IMPORTANT]
> `uploadSingleCV` cần được kiểm tra thread-safety trước khi song song hóa.
> Cụ thể: `successCount++` phải dùng `AtomicInteger`.

---

## Fix 2 — Batch Counter Sai Khi Retry (Ưu tiên: 🟡 Trung bình)

### Nguyên nhân
`incrementProcessed(batchId, isSuccess)` chỉ **cộng**, không bao giờ **trừ**.
Khi 1 CV fail → retry → success: `failedCv` tăng 1, `successCv` tăng 1 → `processedCv` = 2 thay vì 1.

### Thay đổi

#### [MODIFY] `ProcessingBatchService.incrementProcessed()`

Thay vì dùng counter tích lũy, **tính trực tiếp từ DB** mỗi lần update:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void incrementProcessed(String batchId, boolean isSuccess) {
    ProcessingBatch batch = batchRepository.findByBatchId(batchId)...;

    // Tính từ DB thực tế, không dùng counter tích lũy
    long actualSuccess = candidateCVRepository
        .countByBatchIdAndCvStatus(batchId, CVStatus.PARSED);
    long actualFailed  = candidateCVRepository
        .countByBatchIdAndCvStatus(batchId, CVStatus.FAILED);

    batch.setSuccessCv((int) actualSuccess);
    batch.setFailedCv((int) actualFailed);

    if (batch.getProcessedCv() >= batch.getTotalCv()) {
        batch.setStatus(BatchStatus.COMPLETED);
        batch.setCompletedAt(LocalDateTime.now());
    }
    batchRepository.save(batch);
}
```

#### [MODIFY] `CandidateCVRepository`
Thêm method:
```java
long countByBatchIdAndCvStatus(String batchId, CVStatus cvStatus);
```

> [!NOTE]
> Cách này robust hơn vì counter luôn phản ánh trạng thái DB thực tế,
> không bị out-of-sync bất kể retry bao nhiêu lần.

---

## Fix 3 — `error_message` Không Xóa Sau Retry Thành Công (Ưu tiên: 🟡 Trung bình)

### Nguyên nhân
`saveParsedCvResult()` không reset `errorMessage` và `failedAt` về `null`
khi CV parse thành công sau một lần fail.

### Thay đổi

#### [MODIFY] `LlamaParseClient.saveParsedCvResult()`
```java
// Thêm 2 dòng:
cv.setErrorMessage(null);   // Xóa error message từ lần fail trước
cv.setFailedAt(null);       // Xóa timestamp lần fail trước
```

---

## Fix 4 — `createdAt` Bị Reset Khi Retry (Ưu tiên: 🟢 Thấp)

### Nguyên nhân
`retryFailedCVsInBatch()` trong `AnalysisService` có dòng:
```java
batch.setCreatedAt(LocalDateTime.now());  // ← Bug: mất thông tin thời gian batch gốc
```

### Thay đổi

#### [MODIFY] `AnalysisService.retryFailedCVsInBatch()`
Xóa dòng `batch.setCreatedAt(LocalDateTime.now())`.
Chỉ update `status` và `failedCv`, không chạm vào `createdAt`.

---

## Fix 5 — SSE Real-time Tracking (Ưu tiên: 🔵 Feature)

### Mô tả
Thay thế cơ chế polling `GET /tracking/{batchId}/status` bằng SSE stream.
FE mở 1 kết nối, BE tự push khi có update.

### Flow

```
FE: new EventSource("/tracking/{batchId}/stream")
                          │
BE: giữ SseEmitter        │
    khi incrementProcessed() ─→ push event tới FE
    khi COMPLETED         ─→ push event cuối + đóng emitter
```

### Thay đổi

#### [NEW] `SseEmitterRegistry.java` (singleton, lưu emitters đang active)
```java
@Component
public class SseEmitterRegistry {
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public void register(String batchId, SseEmitter emitter) { ... }
    public void send(String batchId, Object data) { ... }
    public void complete(String batchId) { ... }
}
```

#### [MODIFY] `ProcessingBatchController.java`
Thêm endpoint SSE:
```java
@GetMapping(value = "/{batchId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamBatchStatus(@PathVariable String batchId) {
    SseEmitter emitter = new SseEmitter(300_000L); // 5 phút timeout
    registry.register(batchId, emitter);

    // Gửi snapshot hiện tại ngay khi kết nối
    BatchStatusResponse current = processingBatchService.getBatchStatus(batchId).getData();
    emitter.send(current);

    return emitter;
}
```

#### [MODIFY] `ProcessingBatchService.incrementProcessed()`
Sau khi save batch, push event qua SSE:
```java
registry.send(batchId, buildBatchStatusResponse(batch));
if (batch.getStatus() == BatchStatus.COMPLETED) {
    registry.complete(batchId);
}
```

> [!NOTE]
> Giữ nguyên endpoint `GET /tracking/{batchId}/status` để FE có thể fallback
> (kiểm tra status khi reconnect sau mất mạng).

> [!WARNING]
> `SseEmitter` không tương thích với Virtual Threads trong một số phiên bản Spring WebMVC.
> Nếu gặp vấn đề, cần disable virtual threads cho endpoint SSE cụ thể này,
> hoặc dùng `Spring WebFlux` (reactive). Cần test kỹ sau khi implement.

---

## Thứ tự implement

| # | Fix | Effort | Risk |
|---|---|---|---|
| 1 | Fix 1a: Tăng timeout Gateway | 1 dòng config | 🟢 Không có |
| 2 | Fix 3: Xóa error_message khi success | 2 dòng code | 🟢 Không có |
| 3 | Fix 4: Xóa createdAt reset | 1 dòng code | 🟢 Không có |
| 4 | Fix 2: Batch counter từ DB | Refactor 1 method | 🟡 Test kỹ |
| 5 | Fix 1b: Song song hóa upload Drive | Refactor 1 method | 🟡 Test thread-safety |
| 6 | Fix 5: SSE implementation | Thêm 2 file, sửa 2 file | 🔴 Test với VT |

## Files cần thay đổi

```
api-gateway/
  └── application-local.yml                    [MODIFY] Fix 1a

recruitment-service/
  ├── controller/
  │   └── ProcessingBatchController.java        [MODIFY] Fix 5 - thêm SSE endpoint
  ├── services/
  │   ├── UploadCVService.java                  [MODIFY] Fix 1b - parallel upload
  │   ├── ProcessingBatchService.java           [MODIFY] Fix 2 - counter from DB
  │   └── AnalysisService.java                  [MODIFY] Fix 4 - xóa createdAt reset
  ├── client/
  │   └── LlamaParseClient.java                 [MODIFY] Fix 3 - clear error_message
  ├── repository/
  │   └── CandidateCVRepository.java            [MODIFY] Fix 2 - thêm countByBatchId
  └── sse/
      └── SseEmitterRegistry.java               [NEW]    Fix 5 - SSE registry
```
