"""Server-side PDF report generation using ReportLab from verified DB records."""

from io import BytesIO
from typing import Any

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle


def _build_table(rows: list[list[str]]) -> Table:
    table = Table(rows, repeatRows=1)
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1E293B")),
                ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
                ("FONTSIZE", (0, 0), (-1, -1), 8),
                ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#CBD5E1")),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F8FAFC")]),
                ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
            ]
        )
    )
    return table


def render_student_report_pdf(
    *,
    institution_name: str,
    student_code: str,
    student_name: str,
    department: str | None,
    attempts_summary: list[dict[str, Any]],
) -> bytes:
    """Render an individual student performance PDF report from verified database records."""
    buffer = BytesIO()
    doc = SimpleDocTemplate(buffer, pagesize=A4)
    styles = getSampleStyleSheet()
    elements = [
        Paragraph(f"{institution_name} — Individual Athletic Performance Report", styles["Title"]),
        Spacer(1, 8),
        Paragraph(f"Student ID: {student_code} | Name: {student_name}", styles["Normal"]),
        Paragraph(f"Department: {department or 'Not specified'}", styles["Normal"]),
        Spacer(1, 12),
    ]

    if not attempts_summary:
        elements.append(Paragraph("No recorded assessment attempts available for this student.", styles["Normal"]))
    else:
        rows = [["Test Type", "Value", "Uncertainty", "Confidence", "Validity", "Approval", "Recorded UTC"]]
        for item in attempts_summary:
            rows.append(
                [
                    str(item["test_type"]),
                    f"{item['value']:.2f} {item['unit']}",
                    f"+/- {item['uncertainty']:.2f}",
                    f"{item['confidence_score']:.2f}",
                    str(item["validity_status"]),
                    str(item["approval_status"]),
                    str(item["recorded_at"])[:19],
                ]
            )
        elements.append(_build_table(rows))

    elements.append(Spacer(1, 12))
    elements.append(
        Paragraph(
            "Note: Measurements reflect calibrated camera assessments and include provenance & approval status. "
            "Biomechanical observations are performance indicators, not medical diagnoses.",
            styles["Italic"],
        )
    )
    doc.build(elements)
    return buffer.getvalue()


def render_examination_report_pdf(
    *,
    institution_name: str,
    examination_title: str,
    examination_status: str,
    total_students_tested: int,
    approved_attempts_count: int,
    test_summaries: list[dict[str, Any]],
    rankings: list[dict[str, Any]],
) -> bytes:
    """Render an institutional examination summary PDF report from verified database records."""
    buffer = BytesIO()
    doc = SimpleDocTemplate(buffer, pagesize=A4)
    styles = getSampleStyleSheet()
    elements = [
        Paragraph(f"{institution_name} — Examination Summary Report", styles["Title"]),
        Spacer(1, 8),
        Paragraph(f"Examination: {examination_title} (Status: {examination_status})", styles["Normal"]),
        Paragraph(
            f"Students Tested (Approved): {total_students_tested} | Approved Attempts: {approved_attempts_count}",
            styles["Normal"],
        ),
        Spacer(1, 12),
    ]

    if not test_summaries:
        elements.append(Paragraph("No approved assessment records available yet for this examination.", styles["Normal"]))
    else:
        summary_rows = [["Test Type", "Count", "Mean", "Best", "Unit"]]
        for ts in test_summaries:
            summary_rows.append(
                [
                    str(ts["test_type"]),
                    str(ts["count"]),
                    f"{ts['average']:.2f}",
                    f"{ts['best']:.2f}",
                    str(ts["unit"]),
                ]
            )
        elements.append(Paragraph("Test-Wise Summary (Approved Best Attempts)", styles["Heading2"]))
        elements.append(_build_table(summary_rows))
        elements.append(Spacer(1, 12))

        if rankings:
            rank_rows = [["Test Type", "Rank", "Student ID", "Student Name", "Best Value", "Standard Category"]]
            for r in rankings[:50]:
                rank_rows.append(
                    [
                        str(r["test_type"]),
                        str(r["rank"]),
                        str(r["institution_student_code"]),
                        str(r["full_name"]),
                        f"{r['value']:.2f} {r['unit']}",
                        str(r.get("category_label") or "No standard configured"),
                    ]
                )
            elements.append(Paragraph("Student Rankings (Approved Results)", styles["Heading2"]))
            elements.append(_build_table(rank_rows))

    doc.build(elements)
    return buffer.getvalue()
