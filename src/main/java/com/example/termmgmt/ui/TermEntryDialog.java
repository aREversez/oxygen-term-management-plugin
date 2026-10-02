package com.example.termmgmt.ui;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;
import com.example.termmgmt.util.I18N;
import com.example.termmgmt.util.IconUtils;
import com.example.termmgmt.util.TermEntryUtils;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

public class TermEntryDialog extends JDialog {

    private JTextField sourceField;
    private JTextField targetField;
    private JComboBox<String> statusCombo;
    private JTextField noteField;
    private boolean confirmed = false;
    private TermEntry termEntry;
    /** 6.2: The status value at dialog-open time; null means the entry never had one. */
    private final String originalStoredStatus;

    public TermEntryDialog(String title, TermEntry existingTerm) {
        super((Frame) null, title, true);
        this.termEntry = existingTerm != null ? existingTerm : new TermEntry();
        this.originalStoredStatus = this.termEntry.getStoredStatusValue();
        initComponents();
    }

    public TermEntryDialog(String title, String editorSelection) {
        super((Frame) null, title, true);
        this.termEntry = new TermEntry();
        this.termEntry.setSourceTerm(editorSelection);
        this.originalStoredStatus = null;
        initComponents();
        targetField.requestFocusInWindow();
    }

    private void initComponents() {
        ImageIcon logoIcon = IconUtils.loadLogo(16);
        if (logoIcon != null) {
            setIconImage(logoIcon.getImage());
        }

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));

        // Determine row count: base 3 + 1 if note field will be shown
        boolean hasNote = termEntry.getExtraFields().containsKey("note");
        int rows = hasNote ? 4 : 3;
        JPanel formPanel = new JPanel(new GridLayout(rows, 2, 8, 8));
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

        JLabel stLabel = new JLabel(I18N.getString("lbl.status"));
        stLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        statusCombo = new JComboBox<>(new String[]{
            I18N.getString("status.preferred"),
            I18N.getString("status.admitted"),
            I18N.getString("status.deprecated")
        });
        // Select current status
        TermStatus current = termEntry.getStatus();
        if (current == TermStatus.ADMITTED) statusCombo.setSelectedIndex(1);
        else if (current == TermStatus.DEPRECATED) statusCombo.setSelectedIndex(2);
        else statusCombo.setSelectedIndex(0);
        formPanel.add(stLabel);
        formPanel.add(statusCombo);

        if (hasNote) {
            JLabel noteLabel = new JLabel(I18N.getString("lbl.note"));
            noteLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
            String noteVal = termEntry.getExtraFields().get("note");
            noteField = new JTextField(noteVal != null ? noteVal : "", 20);
            formPanel.add(noteLabel);
            formPanel.add(noteField);
        }

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

        // Enter → confirm, ESC → cancel
        getRootPane().setDefaultButton(okButton);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
    }

    private void confirm() {
        String source = sourceField.getText().trim();
        if (source.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.source.required"),
                I18N.getString("msg.validation.error"), JOptionPane.ERROR_MESSAGE);
            return;
        }

        String target = targetField.getText().trim();
        if (target.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.target.required"),
                I18N.getString("msg.validation.error"), JOptionPane.ERROR_MESSAGE);
            return;
        }

        termEntry.setSourceTerm(source);
        termEntry.setTargetTerm(target);

        // Map combo selection to TermStatus.
        // 6.2: Preferred (index 0) on an entry that never had a status writes nothing, so a
        // plain confirm cannot introduce a status column / termNote. The decision lives in
        // TermEntryUtils.resolveDialogStatus so it is unit-testable without a Swing dialog.
        int idx = statusCombo.getSelectedIndex();
        TermStatus toApply = TermEntryUtils.resolveDialogStatus(idx, originalStoredStatus);
        if (toApply != null) {
            termEntry.setStatus(toApply);
        }

        // Update note field if it was shown
        if (noteField != null) {
            termEntry.getExtraFields().put("note", noteField.getText().trim());
        }

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
