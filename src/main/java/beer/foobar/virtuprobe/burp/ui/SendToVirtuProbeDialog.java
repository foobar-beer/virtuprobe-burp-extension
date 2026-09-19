package beer.foobar.virtuprobe.burp.ui;

import beer.foobar.virtuprobe.burp.model.BridgeTargets;
import beer.foobar.virtuprobe.burp.model.SendChoice;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.List;
import java.util.Optional;

/**
 * Asks where a capture should land in VirtuProbe before sending it.
 *
 * <p>Send to VirtuProbe used to post with no bundle, so every capture went wherever the server
 * resolved a default; Jakub's words were that it "just lands somewhere". The dialog opens on the last
 * destination used, which is why it is cheap enough to show on every send.
 *
 * <p><b>It opens even when the destination list could not be loaded.</b> Refusing to open would put
 * this feature back where it started, failing with nothing on screen, so an unreachable VirtuProbe is
 * shown in the dialog with Send disabled.
 */
public class SendToVirtuProbeDialog extends JDialog {

    /** What the user chose. {@code newBundleName} is set instead of a bundle id when creating one. */
    public record Result(SendChoice choice, String newBundleName) {

        public boolean createsBundle() {
            return newBundleName != null && !newBundleName.isBlank();
        }
    }

    /** The "create one" entry in the bundle list, rather than a separate control nobody notices. */
    private static final String NEW_BUNDLE = "+ New bundle...";
    private static final String NO_PROJECT = "(no project)";

    private final BridgeTargets targets;
    private final JComboBox<String> projectBox = new JComboBox<>();
    private final JComboBox<String> bundleBox = new JComboBox<>();
    private final JTextField newBundleField = new JTextField(18);
    private final JLabel newBundleLabel = new JLabel("New bundle name");
    private final JRadioButton structured = new JRadioButton("Structured (editable fields)", true);
    private final JRadioButton verbatim = new JRadioButton("Verbatim (byte for byte)");
    private final JCheckBox promote = new JCheckBox("Also create a regression test asserting this status");
    private final JButton send = new JButton("Send");

    private Result result;

    private SendToVirtuProbeDialog(Window parent, BridgeTargets targets, int requestCount,
                                   SendChoice last, String loadError) {
        super(parent, "Send to VirtuProbe", Dialog.ModalityType.APPLICATION_MODAL);
        this.targets = targets == null ? BridgeTargets.empty() : targets;
        buildLayout(requestCount, loadError);
        populate(last, loadError != null);
        pack();
        setLocationRelativeTo(parent);
    }

    /**
     * Shows the dialog and returns the choice, or empty when cancelled. Must be called on the event
     * thread; the caller loads the targets off it first, because a network call here would freeze Burp.
     *
     * @param loadError why the destination list is missing, or null when it loaded.
     */
    public static Optional<Result> ask(Window parent, BridgeTargets targets, int requestCount,
                                       SendChoice last, String loadError) {
        final SendToVirtuProbeDialog dialog =
                new SendToVirtuProbeDialog(parent, targets, requestCount, last, loadError);
        dialog.setVisible(true);
        return Optional.ofNullable(dialog.result);
    }

    private void buildLayout(int requestCount, String loadError) {
        final JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(14, 14, 8, 14));
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        int row = 0;
        if (loadError != null) {
            c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
            form.add(new JLabel(loadError), c);
            c.gridwidth = 1;
        }

        c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
        form.add(new JLabel(requestCount == 1
                ? "Import 1 request into VirtuProbe."
                : "Import " + requestCount + " requests into VirtuProbe."), c);
        c.gridwidth = 1;

        row = addRow(form, c, row, "Project", projectBox);
        row = addRow(form, c, row, "Bundle", bundleBox);
        row = addRow(form, c, row, newBundleLabel, newBundleField);

        final ButtonGroup modes = new ButtonGroup();
        modes.add(structured);
        modes.add(verbatim);
        final JPanel modeBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        modeBox.add(structured);
        modeBox.add(Box.createHorizontalStrut(10));
        modeBox.add(verbatim);
        row = addRow(form, c, row, "Mode", modeBox);

        c.gridx = 1; c.gridy = row++; c.gridwidth = 2;
        form.add(promote, c);
        if (requestCount != 1) {
            // Promotion seeds an assertion from one observed response, so it is meaningless for a
            // selection. Disabled with the reason, rather than silently ignored on send.
            promote.setEnabled(false);
            promote.setSelected(false);
            promote.setToolTipText("Only available for a single request: the assertion comes from that "
                    + "request's own response.");
        }

        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        send.addActionListener(e -> confirm());
        buttons.add(cancel);
        buttons.add(send);
        getRootPane().setDefaultButton(send);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    private int addRow(JPanel form, GridBagConstraints c, int row, String label, Component field) {
        return addRow(form, c, row, new JLabel(label), field);
    }

    private int addRow(JPanel form, GridBagConstraints c, int row, JLabel label, Component field) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1;
        form.add(label, c);
        c.gridx = 1;
        form.add(field, c);
        return row + 1;
    }

    private void populate(SendChoice last, boolean failed) {
        final SendChoice remembered = last == null ? SendChoice.defaults() : last;

        final DefaultComboBoxModel<String> projects = new DefaultComboBoxModel<>();
        projects.addElement(NO_PROJECT);
        for (BridgeTargets.Project project : targets.safeProjects()) {
            projects.addElement(project.name());
        }
        projectBox.setModel(projects);
        projectBox.setSelectedItem(nameOfProject(remembered.projectId()));
        projectBox.addActionListener(e -> refreshBundles(null));

        refreshBundles(remembered.bundleId());
        bundleBox.addActionListener(e -> updateNewBundleVisibility());

        verbatim.setSelected(remembered.verbatim());
        structured.setSelected(!remembered.verbatim());
        if (promote.isEnabled()) {
            promote.setSelected(remembered.promote());
        }

        updateNewBundleVisibility();
        if (failed) {
            send.setEnabled(false);
        }
    }

    /**
     * Rebuilds the bundle list for the selected project.
     *
     * @param preferredBundleId a bundle to re-select if it is still visible. A remembered id that
     *                          names a deleted bundle is dropped rather than honoured, because a stale
     *                          preference must not be able to send a capture to a bundle that is gone.
     */
    private void refreshBundles(String preferredBundleId) {
        final String projectId = selectedProjectId();
        final List<BridgeTargets.Bundle> visible = targets.bundlesFor(projectId);

        final DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        for (BridgeTargets.Bundle bundle : visible) {
            model.addElement(bundle.name());
        }
        model.addElement(NEW_BUNDLE);
        bundleBox.setModel(model);

        final String preferredName = preferredBundleId == null ? null
                : visible.stream()
                        .filter(b -> preferredBundleId.equals(b.id()))
                        .map(BridgeTargets.Bundle::name)
                        .findFirst().orElse(null);
        if (preferredName != null) {
            bundleBox.setSelectedItem(preferredName);
        } else if (visible.isEmpty()) {
            bundleBox.setSelectedItem(NEW_BUNDLE);
        }
        updateNewBundleVisibility();
    }

    private void updateNewBundleVisibility() {
        final boolean creating = NEW_BUNDLE.equals(bundleBox.getSelectedItem());
        newBundleLabel.setVisible(creating);
        newBundleField.setVisible(creating);
    }

    private String selectedProjectId() {
        final Object selected = projectBox.getSelectedItem();
        if (selected == null || NO_PROJECT.equals(selected)) {
            return null;
        }
        return targets.safeProjects().stream()
                .filter(p -> selected.equals(p.name()))
                .map(BridgeTargets.Project::id)
                .findFirst().orElse(null);
    }

    private String selectedBundleId() {
        final Object selected = bundleBox.getSelectedItem();
        if (selected == null || NEW_BUNDLE.equals(selected)) {
            return null;
        }
        return targets.bundlesFor(selectedProjectId()).stream()
                .filter(b -> selected.equals(b.name()))
                .map(BridgeTargets.Bundle::id)
                .findFirst().orElse(null);
    }

    private String nameOfProject(String projectId) {
        if (projectId == null) {
            return NO_PROJECT;
        }
        return targets.safeProjects().stream()
                .filter(p -> projectId.equals(p.id()))
                .map(BridgeTargets.Project::name)
                .findFirst().orElse(NO_PROJECT);
    }

    private void confirm() {
        final boolean creating = NEW_BUNDLE.equals(bundleBox.getSelectedItem());
        final String newName = newBundleField.getText().trim();
        if (creating && newName.isEmpty()) {
            newBundleField.requestFocusInWindow();
            return;
        }
        final String bundleId = creating ? null : selectedBundleId();
        result = new Result(
                new SendChoice(selectedProjectId(), bundleId, verbatim.isSelected(), promote.isSelected()),
                creating ? newName : null);
        dispose();
    }
}
