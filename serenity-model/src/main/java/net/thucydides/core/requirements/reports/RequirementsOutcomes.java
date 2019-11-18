package net.thucydides.core.requirements.reports;

import net.serenitybdd.core.collect.NewList;
import net.thucydides.core.ThucydidesSystemProperty;
import net.thucydides.core.issues.IssueTracking;
import net.thucydides.core.model.*;
import net.thucydides.core.releases.ReleaseManager;
import net.thucydides.core.reports.TestOutcomes;
import net.thucydides.core.reports.html.ReportNameProvider;
import net.thucydides.core.requirements.ExcludedUnrelatedRequirementTypes;
import net.thucydides.core.requirements.RequirementsTagProvider;
import net.thucydides.core.requirements.model.NarrativeReader;
import net.thucydides.core.requirements.model.OverviewReader;
import net.thucydides.core.requirements.model.Requirement;
import net.thucydides.core.util.EnvironmentVariables;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static net.thucydides.core.ThucydidesSystemProperty.SERENITY_EXCLUDE_UNRELATED_REQUIREMENTS_OF_TYPE;
import static org.apache.commons.lang3.StringUtils.isEmpty;

/**
 * A set of test results for a list of high-level requirements.
 */
public class RequirementsOutcomes {
    private final List<RequirementOutcome> requirementOutcomes;
    private final TestOutcomes testOutcomes;
    private final Optional<Requirement> parentRequirement;
    private final EnvironmentVariables environmentVariables;
    private final IssueTracking issueTracking;
    private final List<? extends RequirementsTagProvider> requirementsTagProviders;
    private final ReleaseManager releaseManager;
    private final ReportNameProvider reportNameProvider;
    List<RequirementOutcome> flattenedRequirementOutcomes = null;
    List<RequirementOutcome> leafRequirementOutcomes = null;
    private final String overview;

    public final static Integer DEFAULT_TESTS_PER_REQUIREMENT = 4;

    private final Map<String, Integer> totalCountCache = new ConcurrentHashMap<>();

    public RequirementsOutcomes(List<Requirement> requirements,
                                TestOutcomes testOutcomes,
                                IssueTracking issueTracking,
                                EnvironmentVariables environmentVariables,
                                List<? extends RequirementsTagProvider> requirementsTagProviders,
                                ReportNameProvider reportNameProvider,
                                String overview) {
        this(null, requirements, testOutcomes, issueTracking, environmentVariables, requirementsTagProviders, reportNameProvider, overview);
    }

    public RequirementsOutcomes(Requirement parentRequirement, List<Requirement> requirements, TestOutcomes testOutcomes,
                                IssueTracking issueTracking, EnvironmentVariables environmentVariables,
                                List<? extends RequirementsTagProvider> requirementsTagProviders,
                                ReportNameProvider reportNameProvider,
                                String overview) {
        this.testOutcomes = testOutcomes;
        this.parentRequirement = Optional.ofNullable(parentRequirement);
        this.environmentVariables = environmentVariables;
        this.issueTracking = issueTracking;
        this.requirementsTagProviders = requirementsTagProviders;
        this.requirementOutcomes = buildRequirementOutcomes(requirements);
        this.reportNameProvider = reportNameProvider;
        this.releaseManager = new ReleaseManager(environmentVariables, reportNameProvider);
        this.overview = overview;
    }

    RequirementsOutcomes(ReportNameProvider reportNameProvider, List<RequirementOutcome> requirementOutcomes, TestOutcomes testOutcomes, Optional<Requirement> parentRequirement, EnvironmentVariables environmentVariables, IssueTracking issueTracking, List<? extends RequirementsTagProvider> requirementsTagProviders, ReleaseManager releaseManager, String overview) {
        this.reportNameProvider = reportNameProvider;
        this.requirementOutcomes = requirementOutcomes;
        this.testOutcomes = testOutcomes;
        this.parentRequirement = parentRequirement;
        this.environmentVariables = environmentVariables;
        this.issueTracking = issueTracking;
        this.requirementsTagProviders = requirementsTagProviders;
        this.releaseManager = releaseManager;
        this.overview = overview;
    }

    private List<RequirementOutcome> buildRequirementOutcomes(List<Requirement> requirements) {

        List<Requirement> distinctRequirements = requirements.stream().distinct().collect(Collectors.toList());

        return distinctRequirements.parallelStream()
                .map(this::requirementOutcomeFor)
                .sorted()
                .collect(Collectors.toList());
    }

    public RequirementOutcome requirementOutcomeFor(Requirement requirement) {
        TestOutcomes outcomesForRequirement = testOutcomes.forRequirement(requirement);

        long requirementsWithoutTests = countRequirementsWithoutTestsIn(requirement);
        long estimatedUnimplementedTests = requirementsWithoutTests * estimatedTestsPerRequirement();
        return new RequirementOutcome(requirement, outcomesForRequirement, requirementsWithoutTests,
                                      estimatedUnimplementedTests, issueTracking);
    }


    RequirementsOutcomesOfTypeCache requirementsOfTypeCache = new RequirementsOutcomesOfTypeCache(this);

    public RequirementsOutcomes requirementsOfType(String type) {
        return requirementsOfTypeCache.byType(type);
    }

    public RequirementsOutcomes ofType(String type) {
        List<Requirement> matchingRequirements = new ArrayList<>();
        List<TestOutcome> matchingTests = new ArrayList<>();

        getFlattenedRequirementOutcomes().stream()
                .filter(requirementOutcome -> requirementOutcome.getRequirement().getType().equalsIgnoreCase(type))
                .forEach(
                        requirementOutcome -> {
                            matchingRequirements.add(requirementOutcome.getRequirement());
                            matchingTests.addAll(requirementOutcome.getTestOutcomes().getOutcomes());
                        }
                );

        return new RequirementsOutcomes(matchingRequirements,
                TestOutcomes.of(matchingTests),
                issueTracking,
                environmentVariables,
                requirementsTagProviders,
                reportNameProvider,
                overview).withoutUnrelatedRequirements();
    }

    private long countRequirementsWithoutTestsIn(Requirement rootRequirement) {
        return getFlattenedRequirements(rootRequirement).stream()
                .filter( requirement -> testOutcomes.forRequirement(requirement).getTotal() == 0)
                .count();
    }

    public int getFlattenedRequirementCount() {
        if (totalIsCachedFor("FlattenedRequirementCount")) { return cachedTotalOf("FlattenedRequirementCount"); }

        int requirementCount = requirementOutcomes.stream()
                .mapToInt(RequirementOutcome::getFlattenedRequirementCount)
                .sum();

        return cachedTotal("FlattenedRequirementCount", requirementCount);
    }

    private int cachedTotal(String key, int total) {
        totalCountCache.put(key, total);
        return total;
    }

    private int cachedTotalOf(String key) {
        return totalCountCache.get(key);
    }

    private boolean totalIsCachedFor(String key) { return totalCountCache.containsKey(key); }

    private List<Requirement> getFlattenedRequirements(Requirement rootRequirement) {
        List<Requirement> flattenedRequirements = new ArrayList<>();
        flattenedRequirements.add(rootRequirement);
        flattenedRequirements.addAll(rootRequirement.getNestedChildren());
        return flattenedRequirements;
    }

    public Optional<Requirement> getParentRequirement() {
        return parentRequirement;
    }

    public java.util.Optional<Requirement> getGrandparentRequirement() {
        if (!parentRequirement.isPresent()) { return java.util.Optional.empty(); }
        if (isEmpty(parentRequirement.get().getParent())) { return java.util.Optional.empty(); }

        return parentRequirementOf(parentRequirement.get());
    }

    private java.util.Optional<Requirement> parentRequirementOf(Requirement requirement) {
        for (RequirementsTagProvider tagProvider : this.requirementsTagProviders) {
            if (tagProvider.getParentRequirementOf(requirement).isPresent()) {
                return tagProvider.getParentRequirementOf(requirement);
            }
        }
        return java.util.Optional.empty();
    }

    public int getRequirementCount() {
        return requirementOutcomes.size();
    }

    public List<RequirementOutcome> getRequirementOutcomes() {
        return NewList.copyOf(requirementOutcomes);
    }

    public String getType() {
        if (requirementOutcomes.isEmpty()) {
            return "requirement";
        } else {
            return requirementOutcomes.get(0).getRequirement().getType();
        }
    }

    public String getChildrenType() {
        return typeOfFirstChildPresent();
    }

    public List<String> getTypes() {
        return getAllRequirements().stream()
                .map(Requirement::getType)
                .distinct()
                .collect(Collectors.toList());
    }

    private String typeOfFirstChildPresent() {
        for (RequirementOutcome outcome : requirementOutcomes) {
            if (!outcome.getRequirement().getChildren().isEmpty()) {
                Requirement firstChildRequirement = outcome.getRequirement().getChildren().get(0);
                return firstChildRequirement.getType();
            }
        }
        return null;
    }

    public TestOutcomes getTestOutcomes() {
        return testOutcomes;
    }

    public Optional<TestResult> getTestResultForTestNamed(String name) {
        Optional<? extends TestOutcome> testOutcome = testOutcomes.testOutcomeWithName(name);
        return testOutcome.map(TestOutcome::getResult);

    }

    @Override
    public String toString() {
        return "RequirementsOutcomes{" +
                "requirementOutcomes=" + requirementOutcomes +
                ", parentRequirement=" + parentRequirement +
                '}';
    }

    public int getCompletedRequirementsCount() {
        if (totalIsCachedFor("CompletedRequirementsCount")) { return cachedTotalOf("CompletedRequirementsCount"); }

        int completedRequirements = 0;
        for (RequirementOutcome requirementOutcome : requirementOutcomes) {
            if (requirementOutcome.isComplete()) {
                completedRequirements++;
            }
        }
        return cachedTotal("CompletedRequirementsCount", completedRequirements);
    }

    public int getUnsuccessfulRequirementsCount() {
        return getErrorRequirementsCount() + getFailingRequirementsCount() + getCompromisedRequirementsCount();
    }

    public int getErrorRequirementsCount() {
        if (totalIsCachedFor("ErrorRequirementsCount")) { return cachedTotalOf("ErrorRequirementsCount"); }

        int matchingRequirements = (int) requirementOutcomes.stream()
                .filter(RequirementOutcome::isError)
                .count();

        return cachedTotal("ErrorRequirementsCount", matchingRequirements);
    }

    public int getFailingRequirementsCount() {
        if (totalIsCachedFor("FailingRequirementsCount")) { return cachedTotalOf("FailingRequirementsCount"); }

        int matchingRequirements = (int) requirementOutcomes.stream()
                .filter(RequirementOutcome::isFailure)
                .count();

        return cachedTotal("FailingRequirementsCount", matchingRequirements);
    }

    public int getPendingRequirementsCount() {
        if (totalIsCachedFor("PendingRequirementsCount")) { return cachedTotalOf("PendingRequirementsCount"); }

        int matchingRequirements = (int) requirementOutcomes.stream()
                .filter(RequirementOutcome::isPending)
                .count();

        return cachedTotal("PendingRequirementsCount", matchingRequirements);
    }

    public int getCompromisedRequirementsCount() {
        if (totalIsCachedFor("CompromisedRequirementsCount")) { return cachedTotalOf("CompromisedRequirementsCount"); }

        int matchingRequirements = (int) requirementOutcomes.stream()
                .filter(RequirementOutcome::isCompromised)
                .count();

        return cachedTotal("CompromisedRequirementsCount", matchingRequirements);
    }

    public int getIgnoredRequirementsCount() {
        if (totalIsCachedFor("IgnoredRequirementsCount")) { return cachedTotalOf("IgnoredRequirementsCount"); }

        int matchingRequirements = (int) requirementOutcomes.stream()
                .filter(RequirementOutcome::isIgnored)
                .count();


        return cachedTotal("IgnoredRequirementsCount", matchingRequirements);
    }

    public int getSkippedRequirementsCount() {
        if (totalIsCachedFor("SkippedRequirementsCount")) { return cachedTotalOf("SkippedRequirementsCount"); }

        int matchingRequirements = (int) requirementOutcomes.stream()
                .filter(RequirementOutcome::isSkipped)
                .count();


        return cachedTotal("SkippedRequirementsCount", matchingRequirements);
    }

    public int getRequirementsWithoutTestsCount() {
        if (totalIsCachedFor("RequirementsWithoutTestsCount")) { return cachedTotalOf("RequirementsWithoutTestsCount"); }

        int requirementsWithNoTests = 0;
        for (Requirement requirement : getTopLevelRequirements()) {
            if (!testsRecordedFor(requirement) && !isPending(requirement)) {
                requirementsWithNoTests++;
            }
        }
        return cachedTotal("IgnoredRequirementsCount", requirementsWithNoTests);
    }

    private boolean isPending(Requirement requirement) {
        for (RequirementOutcome requirementOutcome : requirementOutcomes) {
            if (requirementOutcome.getRequirement().equals(requirement) && requirementOutcome.isPending()) {
                return true;
            }
        }
        return false;
    }

    private boolean testsRecordedFor(Requirement requirement) {
        for (RequirementOutcome outcome : requirementOutcomes) {
            if (outcome.testsRequirement(requirement) && outcome.getTestCount() > 0) {
                return true;
            }
        }
        return false;
    }

    private List<Requirement> getAllRequirements() {
        List<Requirement> allRequirements = new ArrayList<>();
        for (RequirementOutcome outcome : requirementOutcomes) {
            addFlattenedRequirements(outcome.getRequirement(), allRequirements);
        }
        return allRequirements;
    }

    private List<Requirement> getTopLevelRequirements() {
        List<Requirement> requirements = new ArrayList<>();
        for (RequirementOutcome outcome : requirementOutcomes) {
            requirements.add(outcome.getRequirement());
        }
        return requirements;
    }

    public int getTotalRequirements() {
        return getAllRequirements().size();
    }

    private void addFlattenedRequirements(Requirement requirement, List<Requirement> allRequirements) {
        allRequirements.add(requirement);
        for (Requirement child : requirement.getChildren()) {
            addFlattenedRequirements(child, allRequirements);
        }
    }

    public List<RequirementOutcome> getFlattenedRequirementOutcomes() {
        if (flattenedRequirementOutcomes == null) {
            flattenedRequirementOutcomes = getFlattenedRequirementOutcomes(requirementOutcomes);
        }
        return flattenedRequirementOutcomes;
    }

    public List<RequirementOutcome> getLeafRequirementOutcomes() {
        if (leafRequirementOutcomes == null) {
            leafRequirementOutcomes = geLeafRequirementOutcomes(getFlattenedRequirementOutcomes(requirementOutcomes));
        }
        return leafRequirementOutcomes;
    }

    public List<RequirementOutcome> geLeafRequirementOutcomes(List<RequirementOutcome> outcomes) {
        return outcomes.stream().filter(outcome -> !outcome.getRequirement().hasChildren()).collect(Collectors.toList());
    }

    public List<RequirementOutcome> getFlattenedRequirementOutcomes(List<RequirementOutcome> outcomes) {
        Set<RequirementOutcome> flattenedOutcomes = new HashSet<>();

        for (RequirementOutcome requirementOutcome : outcomes) {
            flattenedOutcomes.add(requirementOutcome);
            for (Requirement requirement : requirementOutcome.getRequirement().getChildren()) {

                TestOutcomes testOutcomesForRequirement = requirementOutcome.getTestOutcomes().forRequirement(requirement);

                flattenedOutcomes.add(new RequirementOutcome(requirement, testOutcomesForRequirement, issueTracking));

                List<Requirement> childRequirements = requirement.getChildren();
                RequirementsOutcomes childOutcomes =
                        new RequirementsOutcomes(childRequirements, testOutcomesForRequirement, issueTracking,
                                environmentVariables, requirementsTagProviders, reportNameProvider, overview).withoutUnrelatedRequirements();
                flattenedOutcomes.addAll(getFlattenedRequirementOutcomes(childOutcomes.getRequirementOutcomes()));
            }
        }

        return NewList.copyOf(flattenedOutcomes);
    }

    public OutcomeCounter getTotal() {
        return count(TestType.ANY);
    }

    public OutcomeCounter count(TestType testType) {
        return new OutcomeCounter(testType, getTestOutcomes());
    }

    public OutcomeCounter count(String testType) {
        return count(TestType.valueOf(testType.toUpperCase()));
    }

    public int getTotalTestCount() {
        return testOutcomes.getTotal();
    }

    /**
     * @return Formatted version of the test coverage metrics
     */
    public RequirementsPercentageFormatter getFormattedPercentage() {
        return new RequirementsPercentageFormatter(getProportion());
    }

    public RequirementsPercentageFormatter getFormattedPercentage(String testType) {
        return new RequirementsPercentageFormatter(proportionOf(testType));
    }

    public RequirementsPercentageFormatter getFormattedPercentage(TestType testType) {
        return new RequirementsPercentageFormatter(proportionOf(testType));
    }

    private int totalEstimatedAndImplementedTests() {
        int totalImplementedTests = getTotalTestCount();
        return totalImplementedTests + getEstimatedUnimplementedTests();
    }

    public int getEstimatedUnimplementedTests() {
        return getRequirementsWithoutTestsCount() * estimatedTestsPerRequirement();
    }

    private int estimatedTestsPerRequirement() {
        return ThucydidesSystemProperty.THUCYDIDES_ESTIMATED_TESTS_PER_REQUIREMENT.integerFrom(environmentVariables, DEFAULT_TESTS_PER_REQUIREMENT);
    }

    public RequirementsProportionCounter getProportion() {
        return proportionOf(TestType.ANY);
    }

    public RequirementsProportionCounter proportionOf(String testType) {
        return proportionOf(TestType.valueOf(testType.toUpperCase()));
    }

    public RequirementsProportionCounter proportionOf(TestType testType) {
        return new RequirementsProportionCounter(testType, testOutcomes, totalEstimatedAndImplementedTests());
    }

    public RequirementsOutcomes getReleasedRequirementsFor(Release release) {
        Set<Requirement> matchingRequirements = new HashSet<>();
        Set<TestOutcome> matchingTestOutcomes = new HashSet<>();

        // Add all test outcomes with a matching release

        List<RequirementOutcome> requirementOutcomes
                = releaseManager.enrichRequirementsOutcomesWithReleaseTags(getRequirementOutcomes());

        for (RequirementOutcome outcome : requirementOutcomes) {
            Collection<String> releaseVersions = outcome.getReleaseVersions();
            if (releaseVersions.contains(release.getName())) {
                List<TestOutcome> outcomesForRelease = outcomesForRelease(outcome.getTestOutcomes().getOutcomes(), release.getName());
                if (!outcomesForRelease.isEmpty()) {
                    matchingTestOutcomes.addAll(outcomesForRelease);
                    matchingRequirements.add(outcome.getRequirement());
                }
            }
        }

        matchingRequirements = removeRequirementsWithoutTestsFrom(matchingRequirements);
        return new RequirementsOutcomes(new ArrayList<>(matchingRequirements),
                TestOutcomes.of(matchingTestOutcomes),
                issueTracking,
                environmentVariables,
                requirementsTagProviders,
                reportNameProvider,
                overview).withoutUnrelatedRequirements();
    }

    private Set<Requirement> removeRequirementsWithoutTestsFrom(Collection<Requirement> requirements) {
        Set<Requirement> prunedRequirements = new HashSet<>();
        for (Requirement requirement : requirements) {
            if (testsExistFor(requirement)) {
                Set<Requirement> prunedChildren = removeRequirementsWithoutTestsFrom(requirement.getChildren());
                prunedRequirements.add(requirement.withChildren(new ArrayList<>(prunedChildren)));
            }
        }
        return prunedRequirements;
    }

    private boolean testsExistFor(Requirement requirement) {
        return !getTestOutcomes().forRequirement(requirement).getOutcomes().isEmpty();
    }

    private List<TestOutcome> outcomesForRelease(List<? extends TestOutcome> outcomes,
                                                 String releaseName) {
        releaseManager.enrichOutcomesWithReleaseTags(outcomes);

        return outcomes.stream()
                .filter(outcome -> outcome.getVersions().contains(releaseName))
                .collect(Collectors.toList());
    }

    public RequirementsOutcomes withoutUnrelatedRequirements() {
        if ("none".equalsIgnoreCase(SERENITY_EXCLUDE_UNRELATED_REQUIREMENTS_OF_TYPE.from(environmentVariables))) {
            return this;
        }
        return new RequirementsOutcomes(
                reportNameProvider,
                pruned(requirementOutcomes),
                testOutcomes,
                parentRequirement,
                environmentVariables,
                issueTracking,
                requirementsTagProviders,
                releaseManager,
                overview);
    }

    private List<RequirementOutcome> pruned(List<RequirementOutcome> requirementOutcomes) {

        return requirementOutcomes.stream()
                .filter(requirementOutcome -> !shouldPrune(requirementOutcome))
                .map(requirementOutcome -> requirementOutcome.withoutUnrelatedRequirements())
                .distinct()
                .collect(Collectors.toList());
    }

    public boolean shouldPrune(RequirementOutcome requirementOutcome) {
        return ((requirementOutcome.getTestCount() == 0)
                 && ExcludedUnrelatedRequirementTypes.definedIn(environmentVariables)
                .excludeUntestedRequirementOfType(requirementOutcome.getRequirement().getType()));
    }

    public String getOverview() {
        return overview;
    }
}
