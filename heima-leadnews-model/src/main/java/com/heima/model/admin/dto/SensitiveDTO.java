package com.heima.model.admin.dto;

import com.heima.model.common.dtos.PageRequestDTO;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.springframework.data.domain.jaxb.SpringDataJaxb;

@Data
public class SensitiveDTO extends PageRequestDTO {

    @ApiModelProperty("敏感词")
    private String name;
}
