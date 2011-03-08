package org.eclipse.mylyn.github.internal;

import static org.eclipse.mylyn.github.internal.GitHubConnectorLogger.createErrorStatus;
import static org.eclipse.mylyn.github.internal.GitHubRepositoryUrlBuilder.buildTaskRepositoryProject;
import static org.eclipse.mylyn.github.internal.GitHubRepositoryUrlBuilder.buildTaskRepositoryUser;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mylyn.tasks.core.ITaskMapping;
import org.eclipse.mylyn.tasks.core.RepositoryResponse;
import org.eclipse.mylyn.tasks.core.RepositoryResponse.ResponseKind;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.AbstractTaskDataHandler;
import org.eclipse.mylyn.tasks.core.data.TaskAttribute;
import org.eclipse.mylyn.tasks.core.data.TaskAttributeMapper;
import org.eclipse.mylyn.tasks.core.data.TaskAttributeMetaData;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.core.data.TaskOperation;

/**
 * 
 * @author Christian Trutz
 */
public class GitHubTaskDataHandler extends AbstractTaskDataHandler {

	private static final String DATA_VERSION = "1";

	private GitHubTaskAttributeMapper taskAttributeMapper = null;
	private final GitHubRepositoryConnector connector;
	private DateFormat dateFormat = SimpleDateFormat.getDateTimeInstance();

	private final DateFormat githubDateFormat = new SimpleDateFormat(
			"yyyy/MM/dd HH:mm:ss Z");

	/**
	 * Create a new data handler instance.
	 * 
	 * @param connector
	 *            - repository connector instance
	 */
	public GitHubTaskDataHandler(GitHubRepositoryConnector connector) {
		this.connector = connector;
	}

	/**
	 * @see org.eclipse.mylyn.tasks.core.data.AbstractTaskDataHandler#getAttributeMapper(org.eclipse.mylyn.tasks.core.TaskRepository)
	 */
	@Override
	public final TaskAttributeMapper getAttributeMapper(
			TaskRepository taskRepository) {
		if (this.taskAttributeMapper == null) {
			this.taskAttributeMapper = new GitHubTaskAttributeMapper(
					taskRepository);
		}
		return this.taskAttributeMapper;
	}

	/**
	 * @see org.eclipse.mylyn.tasks.core.data.AbstractTaskDataHandler#initializeTaskData(org.eclipse.mylyn.tasks.core.TaskRepository,
	 *      org.eclipse.mylyn.tasks.core.data.TaskData,
	 *      org.eclipse.mylyn.tasks.core.ITaskMapping,
	 *      org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public final boolean initializeTaskData(TaskRepository repository,
			TaskData data, ITaskMapping initializationData,
			IProgressMonitor monitor) throws CoreException {

		data.setVersion(DATA_VERSION);

		for (GitHubTaskAttributes attr : GitHubTaskAttributes.values()) {
			if (attr.isInitTask()) {
				createAttribute(data, attr, null);
			}
		}

		return true;
	}

	/**
	 * Post a task data.
	 * 
	 * @see org.eclipse.mylyn.tasks.core.data.AbstractTaskDataHandler#postTaskData(org.eclipse.mylyn.tasks.core.TaskRepository,
	 *      org.eclipse.mylyn.tasks.core.data.TaskData, java.util.Set,
	 *      org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public final RepositoryResponse postTaskData(TaskRepository repository,
			TaskData taskData, Set<TaskAttribute> oldAttributes,
			IProgressMonitor monitor) throws CoreException {

		GitHubIssue issue = createIssue(taskData);
		String user = buildTaskRepositoryUser(repository.getUrl());
		String repo = buildTaskRepositoryProject(repository.getUrl());
		try {

			GitHubService service = connector.getService();
			GitHubCredentials credentials = GitHubCredentials
					.create(repository);
			if (taskData.isNew()) {
				issue = service
						.openIssueForView(user, repo, issue, credentials);
			} else {
				TaskAttribute operationAttribute = taskData.getRoot()
						.getAttribute(TaskAttribute.OPERATION);

				GitHubTaskOperation operation = null;

				if (operationAttribute != null) {
					String opId = operationAttribute.getValue();
					operation = GitHubTaskOperation.fromId(opId);

				}
				if (operation != null && operation != GitHubTaskOperation.LEAVE) {
					service.openIssueForEdit(user, repo, issue, credentials);
					switch (operation) {
					case REOPEN:
						service.reopenIssue(user, repo, issue, credentials);
						break;
					case CLOSE:
						service.closeIssue(user, repo, issue, credentials);
						break;
					default:
						throw new IllegalStateException("not implemented: "
								+ operation);
					}
				} else {
					service.openIssueForEdit(user, repo, issue, credentials);
				}
			}
			return new RepositoryResponse(
					taskData.isNew() ? ResponseKind.TASK_CREATED
							: ResponseKind.TASK_UPDATED, issue.getNumber());
		} catch (GitHubServiceException e) {
			throw new CoreException(createErrorStatus(e));
		}

	}

	/**
	 * Create a partial task
	 * 
	 * @param repository
	 *            - repository instance
	 * @param monitor
	 *            - monitor object
	 * @param user
	 *            - user
	 * @param project
	 *            - project
	 * @param issue
	 *            - issue instance
	 * @return a new task data.
	 */
	public final TaskData createTaskData(TaskRepository repository,
			IProgressMonitor monitor, String user, String project,
			GitHubIssue issue, boolean isPartialData) {

		TaskData data = new TaskData(getAttributeMapper(repository),
				GitHub.CONNECTOR_KIND, repository.getRepositoryUrl(),
				issue.getNumber());
		data.setVersion(DATA_VERSION);

		createOperations(data, issue);

		createAttribute(data, GitHubTaskAttributes.KEY, issue.getNumber());
		createAttribute(data, GitHubTaskAttributes.TITLE, issue.getTitle());
		createAttribute(data, GitHubTaskAttributes.BODY, issue.getBody());
		createAttribute(data, GitHubTaskAttributes.STATUS, issue.getState());
		createAttribute(data, GitHubTaskAttributes.CREATION_DATE,
				toLocalDate(issue.getCreatedAt()));
		createAttribute(data, GitHubTaskAttributes.MODIFICATION_DATE,
				toLocalDate(issue.getCreatedAt()));
		createAttribute(data, GitHubTaskAttributes.CLOSED_DATE,
				toLocalDate(issue.getClosedAt()));
		createLabelAttribute(data, GitHubTaskAttributes.LABEL,
				issue.getLabels());
		createVotesAttribute(data, GitHubTaskAttributes.VOTES, issue.getVotes());
		createAttribute(data, GitHubTaskAttributes.REPORTED_BY, issue.getUser());

		if (isPartial(data)) {
			data.setPartial(isPartialData);
		}

		return data;
	}

	private void createVotesAttribute(TaskData data,
			GitHubTaskAttributes attribute, Integer value) {
		TaskAttribute attr = data.getRoot().createAttribute(attribute.getId());
		TaskAttributeMetaData metaData = attr.getMetaData();
		metaData.defaults().setType(attribute.getType())
				.setKind(attribute.getKind()).setLabel(attribute.getLabel())
				.setReadOnly(attribute.isReadOnly());

		if (value != null) {
			attr.setValue(String.valueOf(value));
		}

	}

	private void createLabelAttribute(TaskData data,
			GitHubTaskAttributes attribute, List<String> labels) {
		TaskAttribute attr = data.getRoot().createAttribute(attribute.getId());
		TaskAttributeMetaData metaData = attr.getMetaData();
		metaData.defaults().setType(attribute.getType())
				.setKind(attribute.getKind()).setLabel(attribute.getLabel())
				.setReadOnly(attribute.isReadOnly());

		if (labels != null) {
			attr.setValues(labels);
		}

	}

	private boolean isPartial(TaskData data) {
		for (GitHubTaskAttributes attribute : GitHubTaskAttributes.values()) {
			if (attribute.isRequiredForFullTaskData()) {
				TaskAttribute taskAttribute = data.getRoot().getAttribute(
						attribute.getId());
				if (taskAttribute == null) {
					return true;
				}
			}
		}
		return false;
	}

	private void createOperations(TaskData data, GitHubIssue issue) {
		TaskAttribute operationAttribute = data.getRoot().createAttribute(
				TaskAttribute.OPERATION);
		operationAttribute.getMetaData().setType(TaskAttribute.TYPE_OPERATION);
		if (!data.isNew() && issue.getState() != null) {
			addOperation(data, issue, GitHubTaskOperation.LEAVE, true);
			if (issue.getState().equals("open")) {
				addOperation(data, issue, GitHubTaskOperation.CLOSE, false);
			} else if (issue.getState().equals("closed")) {
				addOperation(data, issue, GitHubTaskOperation.REOPEN, false);
			}
		}
	}

	private void addOperation(TaskData data, GitHubIssue issue,
			GitHubTaskOperation operation, boolean asDefault) {
		TaskAttribute attribute = data.getRoot().createAttribute(
				TaskAttribute.PREFIX_OPERATION + operation.getId());
		String label = createOperationLabel(issue, operation);
		TaskOperation.applyTo(attribute, operation.getId(), label);

		if (asDefault) {
			TaskAttribute operationAttribute = data.getRoot().getAttribute(
					TaskAttribute.OPERATION);
			TaskOperation.applyTo(operationAttribute, operation.getId(), label);
		}
	}

	private String createOperationLabel(GitHubIssue issue,
			GitHubTaskOperation operation) {
		return operation == GitHubTaskOperation.LEAVE ? operation.getLabel()
				+ issue.getState() : operation.getLabel();
	}

	private String toLocalDate(String date) {
		String localDate = date;
		if (date != null && date.trim().length() > 0) {
			// expect "2010/02/02 22:58:39 -0800"
			try {
				Date d = githubDateFormat.parse(date);
				localDate = dateFormat.format(d);
			} catch (ParseException e) {
				// ignore
			}
		}
		return localDate;
	}

	private String toGitHubDate(TaskData taskData, GitHubTaskAttributes attr) {
		TaskAttribute attribute = taskData.getRoot().getAttribute(attr.name());
		String value = attribute == null ? null : attribute.getValue();
		if (value != null) {
			try {
				Date d = dateFormat.parse(value);
				value = githubDateFormat.format(d);
			} catch (ParseException e) {
				// ignore
			}
		}
		return value;
	}

	private GitHubIssue createIssue(TaskData taskData) {
		GitHubIssue issue = new GitHubIssue();
		if (!taskData.isNew()) {
			issue.setNumber(taskData.getTaskId());
		}
		issue.setBody(getAttributeValue(taskData, GitHubTaskAttributes.BODY));
		issue.setTitle(getAttributeValue(taskData, GitHubTaskAttributes.TITLE));
		issue.setState(getAttributeValue(taskData, GitHubTaskAttributes.STATUS));
		issue.setCreatedAt(toGitHubDate(taskData,
				GitHubTaskAttributes.CREATION_DATE));
		issue.setCreatedAt(toGitHubDate(taskData,
				GitHubTaskAttributes.MODIFICATION_DATE));
		issue.setCreatedAt(toGitHubDate(taskData,
				GitHubTaskAttributes.CLOSED_DATE));
		issue.setLabels(toGitHubLabel(taskData, GitHubTaskAttributes.LABEL));
		return issue;
	}

	private List<String> toGitHubLabel(TaskData taskData,
			GitHubTaskAttributes attr) {
		TaskAttribute attribute = taskData.getRoot().getAttribute(attr.name());
		String value = attribute == null ? null : attribute.getValue();
		List<String> labels = new ArrayList<String>();
		labels.add(value);
		return labels;
	}

	private String getAttributeValue(TaskData taskData,
			GitHubTaskAttributes attr) {
		TaskAttribute attribute = taskData.getRoot().getAttribute(attr.getId());
		return attribute == null ? null : attribute.getValue();
	}

	private void createAttribute(TaskData data, GitHubTaskAttributes attribute,
			String value) {
		TaskAttribute attr = data.getRoot().createAttribute(attribute.getId());
		TaskAttributeMetaData metaData = attr.getMetaData();
		metaData.defaults().setType(attribute.getType())
				.setKind(attribute.getKind()).setLabel(attribute.getLabel())
				.setReadOnly(attribute.isReadOnly());

		if (value != null) {
			attr.addValue(value);
		}
	}

}
