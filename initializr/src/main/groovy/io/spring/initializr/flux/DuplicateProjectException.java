package io.spring.initializr.flux;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value=HttpStatus.CONFLICT, reason="Project already exists")
public class DuplicateProjectException extends RuntimeException {

	private static final long serialVersionUID = 5840045881354289124L;
	
	@SuppressWarnings("unused")
	private String projectName;
	private Set<String> allProjects;

	public DuplicateProjectException(String msg, String projectName, Set<String> allProjects) {
		super(msg);
		this.projectName = projectName;
		this.allProjects = allProjects;
	}

	@Override
	public String getMessage() {
		StringBuilder sb = new StringBuilder(super.getMessage());
		sb.append(" Choose a different name for the project excluding names of the existing projects. Existing projects names are");
		for (String project : allProjects) {
			sb.append(" '");
			sb.append(project);
			sb.append("'");
		}
		sb.append(".");
		return sb.toString();
	}

}
