package com.hz.crm.common;

import java.util.List;

import org.springframework.data.domain.Page;

public record PageResult<T>(List<T> content, int page, int totalPages, long totalElements) {

	public static <T> PageResult<T> of(Page<T> p) {
		return new PageResult<>(p.getContent(), p.getNumber(), p.getTotalPages(), p.getTotalElements());
	}
}
