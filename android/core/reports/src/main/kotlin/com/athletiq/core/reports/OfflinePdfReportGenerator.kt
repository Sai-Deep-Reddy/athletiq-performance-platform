package com.athletiq.core.reports

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.athletiq.core.model.AttemptRecord
import com.athletiq.core.model.ExaminationSummary
import com.athletiq.core.model.StudentProfile
import java.io.File
import java.io.FileOutputStream

/**
 * Generates offline individual student and institutional examination PDF reports
 * using Android's native [PdfDocument] API strictly from verified local records.
 */
class OfflinePdfReportGenerator {
    companion object {
        private const val PAGE_WIDTH = 595 // A4 @ 72 dpi
        private const val PAGE_HEIGHT = 842
        private const val MARGIN = 40f
        private const val LINE_HEIGHT = 18f
    }

    fun generateStudentReport(
        institutionName: String,
        student: StudentProfile,
        attempts: List<AttemptRecord>,
        outputFile: File,
    ): File {
        val document = PdfDocument()
        try {
            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 16f
                isFakeBoldText = true
            }
            val bodyPaint = Paint().apply {
                color = Color.DKGRAY
                textSize = 10.5f
            }
            val notePaint = Paint().apply {
                color = Color.GRAY
                textSize = 9f
            }

            var pageNumber = 1
            var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            var canvas = page.canvas
            var y = MARGIN + 16f

            canvas.drawText("$institutionName — Student Performance Report", MARGIN, y, titlePaint)
            y += LINE_HEIGHT * 1.5f
            canvas.drawText("Student ID: ${student.institutionStudentCode} | Name: ${student.fullName}", MARGIN, y, bodyPaint)
            y += LINE_HEIGHT
            canvas.drawText("Department: ${student.department ?: "Not specified"}", MARGIN, y, bodyPaint)
            y += LINE_HEIGHT * 1.5f

            if (attempts.isEmpty()) {
                canvas.drawText("No recorded attempts available for this student.", MARGIN, y, bodyPaint)
                y += LINE_HEIGHT
            } else {
                canvas.drawText("Test | Value | Uncertainty | Confidence | Validity | Approval", MARGIN, y, titlePaint.apply { textSize = 10.5f })
                y += LINE_HEIGHT

                for (attempt in attempts) {
                    if (y > PAGE_HEIGHT - MARGIN * 2) {
                        document.finishPage(page)
                        pageNumber++
                        page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                        canvas = page.canvas
                        y = MARGIN + 16f
                    }
                    val rowText = "${attempt.testType} | ${"%.2f".format(attempt.value)} ${attempt.unit} | " +
                        "+/-${"%.2f".format(attempt.uncertainty)} | ${"%.2f".format(attempt.confidenceScore)} | " +
                        "${attempt.validityStatus} | ${attempt.approvalStatus}"
                    canvas.drawText(rowText, MARGIN, y, bodyPaint)
                    y += LINE_HEIGHT
                }
            }

            y += LINE_HEIGHT
            canvas.drawText(
                "Disclaimer: Measurements include calibration provenance & approval status; not medical diagnoses.",
                MARGIN,
                y.coerceAtMost(PAGE_HEIGHT - MARGIN),
                notePaint,
            )
            document.finishPage(page)

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { fos ->
                document.writeTo(fos)
            }
            return outputFile
        } finally {
            document.close()
        }
    }

    fun generateExaminationReport(
        institutionName: String,
        examination: ExaminationSummary,
        attempts: List<AttemptRecord>,
        studentsById: Map<String, StudentProfile>,
        outputFile: File,
    ): File {
        val document = PdfDocument()
        try {
            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 15f
                isFakeBoldText = true
            }
            val bodyPaint = Paint().apply {
                color = Color.DKGRAY
                textSize = 10f
            }

            val approvedBest = attempts.filter {
                it.approvalStatus.name == "APPROVED" && it.isBestAttempt
            }

            var pageNumber = 1
            var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            var canvas = page.canvas
            var y = MARGIN + 16f

            canvas.drawText("$institutionName — Examination Summary", MARGIN, y, titlePaint)
            y += LINE_HEIGHT * 1.4f
            canvas.drawText("Exam: ${examination.title} (${examination.status})", MARGIN, y, bodyPaint)
            y += LINE_HEIGHT
            canvas.drawText("Approved Best Records: ${approvedBest.size} | Total Recorded Attempts: ${attempts.size}", MARGIN, y, bodyPaint)
            y += LINE_HEIGHT * 1.5f

            if (approvedBest.isEmpty()) {
                canvas.drawText("No examiner-approved best attempts recorded yet.", MARGIN, y, bodyPaint)
            } else {
                for (att in approvedBest) {
                    if (y > PAGE_HEIGHT - MARGIN * 2) {
                        document.finishPage(page)
                        pageNumber++
                        page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                        canvas = page.canvas
                        y = MARGIN + 16f
                    }
                    val stu = studentsById[att.studentId]
                    val label = stu?.let { "${it.institutionStudentCode} (${it.fullName})" } ?: att.studentId
                    canvas.drawText(
                        "$label | ${att.testType}: ${"%.2f".format(att.value)} ${att.unit} (+/-${"%.2f".format(att.uncertainty)})",
                        MARGIN,
                        y,
                        bodyPaint,
                    )
                    y += LINE_HEIGHT
                }
            }

            document.finishPage(page)
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { fos ->
                document.writeTo(fos)
            }
            return outputFile
        } finally {
            document.close()
        }
    }
}
