package org.example.recruitmentservice.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.services.StorageService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Nightly job (2 AM) to purge Drive files for permanently FAILED CVs.
 *
 * Design rationale:
 * - DB records are intentionally preserved for audit and error visibility on
 * the dashboard.
 * - Only the physical Drive file is deleted to reclaim storage quota.
 * - {@code deletedAt} is set on the record so the job is idempotent — a re-run
 * never
 * attempts to delete an already-cleaned file.
 * - Processing is done record-by-record (not in bulk) to ensure each Drive
 * deletion is
 * confirmed before the DB flag is committed, preventing orphaned files on
 * partial failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GarbageCollectionJob {

    private final CandidateCVRepository candidateCVRepository;
    private final StorageService storageService;

    @Scheduled(cron = "0 0 2 * * *")
    public void purgeFailedCVFiles() {
        List<CandidateCV> candidates = candidateCVRepository.findFailedCVsPendingCleanup();

        if (candidates.isEmpty()) {
            log.info("[GC-JOB] No failed CVs pending Drive cleanup.");
            return;
        }

        log.info("[GC-JOB] Starting Drive cleanup for {} failed CV(s).", candidates.size());
        int deleted = 0;
        int skipped = 0;

        for (CandidateCV cv : candidates) {
            try {
                deleteDriveFile(cv);
                deleted++;
            } catch (Exception e) {
                log.error("[GC-JOB] Failed to delete Drive file for CV {}: {}", cv.getId(), e.getMessage());
                skipped++;
                // Continue processing others; this CV will be retried in the next GC run
            }
        }

        log.info("[GC-JOB] Cleanup complete — deleted: {}, skipped (retried next run): {}", deleted, skipped);
    }

    /**
     * Deletes the Drive file and marks the DB record as cleaned up.
     * Each CV is committed in its own transaction so a failure on one does not
     * roll back progress already made on others.
     */
    @Transactional
    public void deleteDriveFile(CandidateCV cv) {
        storageService.deleteFile(cv.getDriveFileId());

        cv.setDeletedAt(LocalDateTime.now());
        candidateCVRepository.save(cv);

        log.info("[GC-JOB] Purged Drive file for CV {} (driveFileId={})", cv.getId(), cv.getDriveFileId());
    }
}
