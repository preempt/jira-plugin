package hudson.plugins.jira.pipeline;

import com.atlassian.jira.rest.client.api.RestClientException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.jira.JiraSession;
import hudson.plugins.jira.JiraSite;
import hudson.plugins.jira.Messages;
import hudson.plugins.jira.model.JiraIssueField;
import hudson.plugins.jira.selector.AbstractIssueSelector;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jdk.nashorn.internal.ir.annotations.Immutable;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Issue custom fields updater
 * 
 * @author Dmitry Frolov tekillaz.dev@gmail.com
 * 
 */
public class IssueFieldUpdateStep extends Builder implements SimpleBuildStep {

    private AbstractIssueSelector issueSelector;

    public AbstractIssueSelector getIssueSelector() {
        return this.issueSelector;
    }

    @DataBoundSetter
    public void setIssueSelector(AbstractIssueSelector issueSelector) {
        this.issueSelector = issueSelector;
    }

    public String fieldId;

    public String getFieldId() {
        return this.fieldId;
    }

    @DataBoundSetter
    public void setFieldId(String fieldId) {
        this.fieldId = fieldId;
    }

    public String fieldValue;

    public String getFieldValue() {
        return this.fieldValue;
    }

    @DataBoundSetter
    public void setFieldValue(String fieldValue) {
        this.fieldValue = fieldValue;
    }

    private boolean fieldTypeMultiple = false;

    public String getFieldTypeMultiple() {
        return Boolean.toString(fieldTypeMultiple);
    }

    @DataBoundSetter
    public void setFieldTypeMultiple(String fieldTypeMultiple) {
        this.fieldTypeMultiple = Boolean.parseBoolean(fieldTypeMultiple);
    }

    @DataBoundConstructor
    public IssueFieldUpdateStep(AbstractIssueSelector issueSelector, String fieldId, String fieldValue, String fieldTypeMultiple) {
        this.issueSelector = issueSelector;
        this.fieldId = fieldId;
        this.fieldValue = fieldValue;
        this.fieldTypeMultiple = Boolean.parseBoolean(fieldTypeMultiple);
    }

    public String prepareFieldId(String fieldId) {
        String prepared = fieldId;
        if (!prepared.startsWith("customfield_"))
            prepared = "customfield_" + prepared;
        return prepared;
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {
        PrintStream logger = listener.getLogger();

        AbstractIssueSelector selector = issueSelector;
        if (selector == null) {
            logger.println("[Jira][IssueFieldUpdateStep] No issue selector found!");
            throw new IOException("[Jira][IssueFieldUpdateStep] No issue selector found!");
        }

        JiraSite site = JiraSite.get(run.getParent());
        if (site == null) {
            logger.println(Messages.NoJiraSite());
            run.setResult(Result.FAILURE);
            return;
        }

        JiraSession session = site.getSession();
        if (session == null) {
            logger.println(Messages.NoRemoteAccess());
            run.setResult(Result.FAILURE);
            return;
        }

        Set<String> issues = selector.findIssueIds(run, site, listener);
        if (issues.isEmpty()) {
            logger.println("[Jira][IssueFieldUpdateStep] Issue list is empty!");
            return;
        }

        List<JiraIssueField> fields = new ArrayList();
        String expandedFieldValue = Util.fixEmptyAndTrim(run.getEnvironment(listener).expand(fieldValue));
        JiraIssueField jiraIssueField;
        if (fieldTypeMultiple && expandedFieldValue != null)
        {
            jiraIssueField = new JiraIssueField(prepareFieldId(fieldId), ImmutableList.of(expandedFieldValue));
        }
        else
        {
            jiraIssueField = new JiraIssueField(prepareFieldId(fieldId), expandedFieldValue);
        }
        fields.add(jiraIssueField);

        for (String issue : issues) {
            submitFields(session, issue, fields, logger);
        }
    }

    public void submitFields(JiraSession session, String issueId, List<JiraIssueField> fields, PrintStream logger) {
        try {
            session.addFields(issueId, fields);
        } catch (RestClientException e) {

            if (e.getStatusCode().or(0).equals(404)) {
                logger.println(issueId + " - Jira issue not found");
            }

            if (e.getStatusCode().or(0).equals(403)) {
                logger.println(issueId
                        + " - Jenkins Jira user does not have permissions to comment on this issue");
            }

            if (e.getStatusCode().or(0).equals(401)) {
                logger.println(
                        issueId + " - Jenkins Jira authentication problem");
            }

            logger.println(Messages.FailedToUpdateIssue(issueId));
            logger.println(e.getLocalizedMessage());
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public FormValidation doCheckField_id(@QueryParameter String value) throws IOException, ServletException {
            if (Util.fixNull(value).trim().length() == 0)
                return FormValidation.warning(Messages.JiraIssueFieldUpdater_NoIssueFieldID());
            if (!value.matches("\\d+"))
                return FormValidation.error(Messages.JiraIssueFieldUpdater_NotAtIssueFieldID());
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.JiraIssueFieldUpdater_DisplayName();
        }
    }

}
