/*
 * Copyright 2009 Christian Trutz 

 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *  
 */
package org.eclipse.mylyn.github.ui.internal;

import java.util.regex.Matcher;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.mylyn.commons.net.AuthenticationCredentials;
import org.eclipse.mylyn.commons.net.AuthenticationType;
import org.eclipse.mylyn.github.internal.GitHub;
import org.eclipse.mylyn.github.internal.GitHubService;
import org.eclipse.mylyn.github.internal.GitHubServiceException;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.ui.wizards.AbstractRepositorySettingsPage;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;

/**
 * GitHub connector specific extensions.
 * 
 * @author Christian Trutz
 * @author Brian Gianforcaro
 * @since 0.1.0
 */
public class GitHubRepositorySettingsPage extends
		AbstractRepositorySettingsPage {

	static final String URL = "http://github.com";

	private static final String PASS_LABEL_TEXT = "GitHub API Token:";

	/**
	 * Populate taskRepository with repository settings.
	 * 
	 * @param taskRepository
	 *            - Object to populate
	 */
	public GitHubRepositorySettingsPage(final TaskRepository taskRepository) {
		super("GitHub Repository Settings", "", taskRepository);
		this.setHttpAuth(false);
		this.setNeedsAdvanced(false);
		this.setNeedsAnonymousLogin(false);
		this.setNeedsTimeZone(false);
		this.setNeedsHttpAuth(false);
	}

	@Override
	public final String getConnectorKind() {
		return GitHub.CONNECTOR_KIND;
	}

	@Override
	protected final void createAdditionalControls(Composite parent) {
		// Set the URL now, because serverURL is definitely instantiated .
		if (serverUrlCombo != null
				&& (serverUrlCombo.getText() == null || serverUrlCombo
						.getText().trim().length() == 0)) {
			String fullUrlText = URL + "/user/project";
			serverUrlCombo.setText(fullUrlText);
			serverUrlCombo.setSelection(new Point(URL.length() + 1, fullUrlText
					.length()));
		}

		// Specify that you need the GitHub User Name
		if (repositoryUserNameEditor != null) {
			String text = repositoryUserNameEditor.getLabelText();
			repositoryUserNameEditor.setLabelText("GitHub " + text);
		}

		// Use the password field for the API Token Key
		if (repositoryPasswordEditor != null) {
			repositoryPasswordEditor.setLabelText(PASS_LABEL_TEXT);
		}
	}

	@Override
	protected final Validator getValidator(final TaskRepository repository) {
		return new SettingsValidator(repository);
	}

	@Override
	protected final boolean isValidUrl(final String url) {
		if (url.contains("github")) {
			return true;
		}
		return false;
	}

	private final class SettingsValidator extends Validator {
		private static final int MONITOR_PROGRESS_400 = 400;
		private static final int MONITOR_PROGRESS_100 = 100;
		private static final int MONITOR_PROGRESS_1000 = 1000;
		private final TaskRepository taskRepository;

		protected SettingsValidator(TaskRepository repository) {
			this.taskRepository = repository;
		}

		@Override
		public void run(IProgressMonitor monitor) throws CoreException {
			int totalWork = MONITOR_PROGRESS_1000;
			monitor.beginTask("Validating settings", totalWork);
			try {
				String urlText = taskRepository.getUrl();
				Matcher urlMatcher = GitHub.URL_PATTERN
						.matcher(urlText == null ? "" : urlText);
				if (!urlMatcher.matches()) {
					setStatus(GitHubUi
							.createErrorStatus("Server URL must be in the form http://github.com/user/project or\nhttps://github.com/user/project"));
					return;
				}
				monitor.worked(MONITOR_PROGRESS_100);
				AuthenticationCredentials auth = taskRepository
						.getCredentials(AuthenticationType.REPOSITORY);
				monitor.subTask("Contacting server...");
				try {
					// verify the repo
					GitHubService.getIssueService(taskRepository).retrieve();
					monitor.worked(MONITOR_PROGRESS_400);
					// verify the credentials
					if (auth == null) {
						setStatus(GitHubUi
								.createErrorStatus("Credentials are required.  Please specify username and API Token."));
						return;
					}
					if (!GitHubService.getUserService(taskRepository).validateCredentials()) {
						setStatus(GitHubUi
								.createErrorStatus("Invalid credentials.  Please check your GitHub User ID and API Token.\nYou can find your API Token on your GitHub account settings page."));
						return;
					}
				} catch (GitHubServiceException e) {
					setStatus(GitHubUi
							.createErrorStatus("Repository Test failed:"
									+ e.getMessage()));
					return;
				}
				setStatus(new Status(IStatus.OK, GitHubUi.BUNDLE_ID, "Success!"));
			} finally {
				monitor.done();
			}
		}

	}

}
