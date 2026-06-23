package com.example.termmgmt.ui;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.service.TermbaseRegistry;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.List;

/**
 * Tab 2: Termbase Search panel.
 *
 * Searches across all enabled termbases for terms.
 *
 * Search logic:
 * - Case-insensitive fuzzy match
 * - Searches both source term and target term
 */
public class TermbaseSearchPanel extends JPanel {

    private JTextField searchField;
    private JTable resultTable;
    private DefaultTableModel tableModel;
    private TermbaseRegistry registry;

    public TermbaseSearchPanel(TermbaseRegistry registry) {
        this.registry = registry;
        initComponents();
    }

    /**
     * Initialize the UI components.
     */
    private void initComponents() {
        setLayout(new BorderLayout());

        // Header + search panel (north area)
        JPanel northPanel = new JPanel();
        northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));
        northPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

        JPanel headerWrap = new JPanel(new BorderLayout());
        JLabel headerLabel = new JLabel("Search across all enabled termbases.");
        headerLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
        headerWrap.add(headerLabel, BorderLayout.CENTER);
        northPanel.add(headerWrap);
        northPanel.add(Box.createVerticalStrut(6));

        JPanel searchPanel = new JPanel(new BorderLayout(4, 0));
        searchPanel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        searchField = new JTextField();
        searchField.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchTerms();
            }
        });
        searchPanel.add(searchField, BorderLayout.CENTER);

        JButton searchButton = new JButton("Search");
        searchButton.addActionListener(e -> searchTerms());
        searchPanel.add(searchButton, BorderLayout.EAST);

        northPanel.add(searchPanel);
        add(northPanel, BorderLayout.NORTH);

        // Create result table
        tableModel = new DefaultTableModel(
            new String[]{"Source", "Target", "Termbase"}, 0
        ) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        resultTable = new JTable(tableModel);
        add(new JScrollPane(resultTable), BorderLayout.CENTER);
    }

    /**
     * Search for terms matching the search field.
     */
    private void searchTerms() {
        String searchTerm = searchField.getText().trim();
        if (searchTerm.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Please enter a search term.",
                "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Clear table
        tableModel.setRowCount(0);

        // Get enabled termbases
        List<TermbaseConfig> enabledConfigs = registry.getEnabledConfigs();

        int matchCount = 0;

        // Search each enabled termbase
        for (TermbaseConfig config : enabledConfigs) {
            List<TermEntry> terms = registry.getTerms(config);
            for (TermEntry term : terms) {
                String sourceTerm = term.getSourceTerm();
                String targetTerm = term.getTargetTerm();

                // Case-insensitive fuzzy match
                if ((sourceTerm != null && sourceTerm.toLowerCase().contains(searchTerm.toLowerCase())) ||
                    (targetTerm != null && targetTerm.toLowerCase().contains(searchTerm.toLowerCase()))) {
                    tableModel.addRow(new Object[]{
                        sourceTerm,
                        targetTerm,
                        config.getFileName()
                    });
                    matchCount++;
                }
            }
        }

        // Show warning only when no termbases are configured
        if (matchCount == 0 && enabledConfigs.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No enabled termbases.",
                "Warning", JOptionPane.WARNING_MESSAGE);
        }
    }
}
