package com.example.termmgmt.ui;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.util.I18N;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class TermEntryDialog extends JDialog {

    private JTextField sourceField;
    private JTextField targetField;
    private boolean confirmed = false;
    private TermEntry termEntry;

    public TermEntryDialog(String title, TermEntry existingTerm) {
        super((Frame) null, title, true);
        this.termEntry = existingTerm != null ? existingTerm : new TermEntry();
        initComponents();
    }

    public TermEntryDialog(String title, String editorSelection) {
        super((Frame) null, title, true);
        this.termEntry = new TermEntry();
        this.termEntry.setSourceTerm(editorSelection);
        initComponents();
    }

    private void initComponents() {
        setIconImage(createDialogIcon());

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));

        JPanel formPanel = new JPanel(new GridLayout(2, 2, 8, 8));
        formPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

        JLabel srcLabel = new JLabel(I18N.getString("lbl.source.term"));
        srcLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        sourceField = new JTextField(termEntry.getSourceTerm() != null ? termEntry.getSourceTerm() : "", 20);
        formPanel.add(srcLabel);
        formPanel.add(sourceField);

        JLabel tgtLabel = new JLabel(I18N.getString("lbl.target.term"));
        tgtLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        targetField = new JTextField(termEntry.getTargetTerm() != null ? termEntry.getTargetTerm() : "", 20);
        formPanel.add(tgtLabel);
        formPanel.add(targetField);

        content.add(formPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton okButton = new JButton(I18N.getString("btn.ok"));
        okButton.addActionListener(e -> confirm());
        buttonPanel.add(okButton);

        JButton cancelButton = new JButton(I18N.getString("btn.cancel"));
        cancelButton.addActionListener(e -> dispose());
        buttonPanel.add(cancelButton);

        content.add(buttonPanel, BorderLayout.SOUTH);

        add(content);
        pack();
        setMinimumSize(new Dimension(380, 160));
        setLocationRelativeTo(null);
    }

    private static Image createDialogIcon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x2B579A));
        g.fillRoundRect(1, 1, 14, 14, 4, 4);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        FontMetrics fm = g.getFontMetrics();
        String letter = "T";
        int x = (16 - fm.stringWidth(letter)) / 2;
        int y = (16 - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(letter, x, y);
        g.dispose();
        return img;
    }

    private void confirm() {
        String source = sourceField.getText().trim();
        if (source.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.source.required"),
                "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        termEntry.setSourceTerm(source);
        termEntry.setTargetTerm(targetField.getText().trim());

        confirmed = true;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public TermEntry getTermEntry() {
        return termEntry;
    }
}
