package com.newbiest.mms.rest.doc.back.create;

import com.newbiest.base.rest.AbstractRestController;
import com.newbiest.mms.service.DocumentService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mms")
@Slf4j
@Api(value="/mms", tags="MaterialManagerSystem", description = "物料管理相关")
public class CreateReturnOrderController extends AbstractRestController {

    @Autowired
    DocumentService documentService;

    @ApiOperation(value = "创建退料单")
    @ApiImplicitParam(name="request", value="request", required = true, dataType = "CreateReturnMLotOrder")
    @RequestMapping(value = "/createReturnMLotOrder", method = RequestMethod.POST, produces = "application/json; charset=utf-8")
    public CreateReturnOrderResponse execute(@RequestBody CreateReturnOrderRequest request) throws Exception {
        CreateReturnOrderResponse response = new CreateReturnOrderResponse();
        response.getHeader().setTransactionId(request.getHeader().getTransactionId());
        CreateReturnOrderResponseBody responseBody = new CreateReturnOrderResponseBody();
        CreateReturnOrderRequestBody requestBody = request.getBody();

        documentService.createReturnOrder(requestBody.getDocumentId(), true, requestBody.getMaterialLotActionList());
        response.setBody(responseBody);
        return response;
    }
}
