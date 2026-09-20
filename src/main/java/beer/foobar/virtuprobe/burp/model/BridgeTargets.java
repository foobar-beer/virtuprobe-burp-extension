package beer.foobar.virtuprobe.burp.model;

import java.util.ArrayList;
import java.util.List;

/**
 * What {@code GET /burp/targets} offers the destination picker: the workspace's bundles and projects.
 *
 * <p>A bundle carries the projects it belongs to rather than the other way round, and an empty
 * {@code projectIds} means the bundle is free and therefore visible from every project. That is
 * VirtuProbe's own visibility rule, so {@link #bundlesFor} mirrors it instead of inventing a second
 * answer the two ends could disagree about.
 */
public record BridgeTargets(List<Bundle> bundles, List<Project> projects) {

    public record Bundle(String id, String name, List<String> projectIds) {

        /** True when this bundle belongs to no project, so every project can see it. */
        public boolean isFree() {
            return projectIds == null || projectIds.isEmpty();
        }
    }

    public record Project(String id, String name) {
    }

    public static BridgeTargets empty() {
        return new BridgeTargets(List.of(), List.of());
    }

    public List<Bundle> safeBundles() {
        return bundles == null ? List.of() : bundles;
    }

    public List<Project> safeProjects() {
        return projects == null ? List.of() : projects;
    }

    /**
     * The bundles a given project can see: its own, plus every free one.
     *
     * @param projectId the selected project, or null for "no project", which shows the free bundles
     *                  only. Those are the ones a capture can land in without belonging anywhere.
     */
    public List<Bundle> bundlesFor(String projectId) {
        final List<Bundle> visible = new ArrayList<>();
        for (Bundle bundle : safeBundles()) {
            if (projectId == null) {
                if (bundle.isFree()) {
                    visible.add(bundle);
                }
            } else if (bundle.isFree() || bundle.projectIds().contains(projectId)) {
                visible.add(bundle);
            }
        }
        return visible;
    }
}
