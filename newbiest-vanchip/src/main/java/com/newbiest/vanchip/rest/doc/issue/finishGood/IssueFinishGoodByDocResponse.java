package com.newbiest.vanchip.rest.doc.issue.finishGood;

import com.newbiest.base.msg.Response;
import lombok.Data;

@Data
public class IssueFinishGoodByDocResponse extends Response {
	
	private static final long serialVersionUID = 1L;
	
	private IssueFinishGoodByDocResponseBody body;
	
}
