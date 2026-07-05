package com.example.termmgmt.ui;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.service.TermbaseRegistry;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Tab 2: Termbase Search panel.
 *
 * Searches across all enabled termbases for terms.
 * Double-click a result to jump to Terminology tab for editing.
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
    private TermManagementView parentView;

    public TermbaseSearchPanel(TermbaseRegistry registry, TermManagementView parentView) {
        this.registry = registry;
        this.parentView = parentView;
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
        resultTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && parentView != null) {
                    int row = resultTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        String source = (String) tableModel.getValueAt(row, 0);
                        String target = (String) tableModel.getValueAt(row, 1);
                        String tbFile = (String) tableModel.getValueAt(row, 2);
                        if (source != null && tbFile != null) {
                            // Find file path from file name
                            for (TermbaseConfig config : registry.getEnabledConfigs()) {
                                if (config.getFileName().equals(tbFile)) {
                                    parentView.switchToTerminology(config.getFilePath(), source, target);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        });
        add(new JScrollPane(resultTable), BorderLayout.CENTER);

        // Hint label at bottom
        JLabel hintLabel = new JLabel("Double-click to locate in Terminology.");
        hintLabel.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));
        hintLabel.setForeground(java.awt.Color.GRAY);
        add(hintLabel, BorderLayout.SOUTH);
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
        executeSearch(searchTerm);
    }

    /**
     * Execute a search with the given term and populate results.
     * Public so it can be triggered externally (e.g. from context menu).
     */
    public void executeSearch(String searchTerm) {
        searchField.setText(searchTerm);
        tableModel.setRowCount(0);

        List<TermbaseConfig> enabledConfigs = registry.getEnabledConfigs();
        int matchCount = 0;

        for (TermbaseConfig config : enabledConfigs) {
            List<TermEntry> terms = registry.getTerms(config);
            for (TermEntry term : terms) {
                String sourceTerm = term.getSourceTerm();
                String targetTerm = term.getTargetTerm();
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

        if (matchCount == 0 && enabledConfigs.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No enabled termbases.",
                "Warning", JOptionPane.WARNING_MESSAGE);
        }
    }
}
