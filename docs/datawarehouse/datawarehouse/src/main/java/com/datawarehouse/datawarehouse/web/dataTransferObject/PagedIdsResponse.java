package com.datawarehouse.datawarehouse.web.dataTransferObject;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;
//raspuns pentru asset si dataSource
@Data
@AllArgsConstructor
public class PagedIdsResponse {
    private List<String> items;
    private int offset;
    private int limit;
    private boolean hasNext;
}
