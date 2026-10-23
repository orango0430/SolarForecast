package kr.solarcast.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Plant {

	@Id
	private Long id;
	private String name;
	private double capacityKw;
	private double lat;
	private double lon;
	private int asosStnId;
	private int nx;
	private int ny;
}
