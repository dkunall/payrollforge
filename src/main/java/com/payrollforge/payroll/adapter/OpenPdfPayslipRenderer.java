package com.payrollforge.payroll.adapter;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.payrollforge.payroll.application.PayslipRenderer;
import com.payrollforge.payroll.application.PayslipRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

/** A plain one-page payslip: earnings and deductions with amounts, then totals. Bank and PAN details are left out. */
@Component
class OpenPdfPayslipRenderer implements PayslipRenderer {

    @Override
    public byte[] render(PayslipRow p, String entity) {
        var out = new ByteArrayOutputStream();
        var doc = new Document(PageSize.A4, 48, 48, 48, 48);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        doc.add(new Paragraph("Payslip " + p.period() + "  (" + entity + ")", title));
        doc.add(new Paragraph(p.code() + "  " + p.name(), body));
        doc.add(new Paragraph("Payable days " + p.inputs().getOrDefault("PAYABLE_DAYS", "-") + " of "
                + p.inputs().getOrDefault("PERIOD_DAYS", "-") + ", loss of pay " + p.inputs().getOrDefault("LOP_DAYS", "0"), body));
        doc.add(new Paragraph(" "));
        var t = new PdfPTable(3);
        t.setWidthPercentage(100);
        for (String h : new String[]{"Component", "Type", "Amount (INR)"}) {
            t.addCell(new PdfPCell(new Phrase(h, bold)));
        }
        for (var l : p.lines()) {
            if ("INFO".equals(l.get("kind"))) {
                continue;
            }
            t.addCell(new PdfPCell(new Phrase(String.valueOf(l.get("label")), body)));
            t.addCell(new PdfPCell(new Phrase(String.valueOf(l.get("kind")), body)));
            var amount = new PdfPCell(new Phrase(String.valueOf(l.get("amount")), body));
            amount.setHorizontalAlignment(Element.ALIGN_RIGHT);
            t.addCell(amount);
        }
        doc.add(t);
        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("Gross " + p.gross().toPlainString() + "   Deductions " + p.deductions().toPlainString()
                + "   Net pay " + p.net().toPlainString(), bold));
        doc.add(new Paragraph("Tax figures use illustrative rules, not current statutory rates.", body));
        doc.close();
        return out.toByteArray();
    }
}
