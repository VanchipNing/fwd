package com.newbiest.vanchip.rest.mobile;

import com.newbiest.base.msg.ResponseBody;
import com.newbiest.mms.model.MaterialLot;
import lombok.Data;

import java.util.List;

@Data
public class MobileResponseBody extends ResponseBody {

	private static final long serialVersionUID = 1L;


	private List<MaterialLot> materialLots;

}
