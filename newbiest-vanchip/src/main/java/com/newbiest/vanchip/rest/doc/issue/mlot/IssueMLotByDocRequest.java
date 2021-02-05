package com.newbiest.vanchip.rest.doc.issue.mlot;

import com.newbiest.base.msg.Request;
import io.swagger.annotations.ApiModel;
import lombok.Data;

@Data
@ApiModel
public class IssueMLotByDocRequest extends Request {

	private static final long serialVersionUID = 1L;
	
	public static final String MESSAGE_NAME = "VCIssueMLotByDoc";

	private IssueMLotByDocRequestBody body;

}
