# 채팅 도면 카드와 길찾기 연결

기존 `POST /api/chats/{id}/messages`와 `GET /api/chats/{id}`의 `messages[]`에 `location`이 추가된다. 일반 대화에서는 null이며 기존 content, attachments, contentFormat은 유지한다.

## 위치 질문

```json
{"content":"보건실 위치 알려줘"}
```

서버는 공개 장소 이름과 위치 의도를 확인해 다음 데이터를 assistant 메시지에 반환한다. LLM이 좌표를 생성하지 않는다.

```json
{
  "role":"ASSISTANT",
  "content":"성고충상담실/보건실은 본관 1층에 있어. 도면에서 표시된 위치를 확인해줘. 길찾기는 출발 층과 위치를 선택해줘.",
  "location":{
    "status":"DESTINATION_READY",
    "requiresOrigin":true,
    "candidates":[{
      "place":{
        "id":"main-1F-02",
        "name":"성고충상담실/보건실",
        "buildingId":1,
        "buildingName":"본관",
        "floor":"1F",
        "floorPlanId":"main-1F",
        "x":75.5,
        "y":122,
        "area":{"x":52,"y":53,"width":47,"height":69}
      },
      "floorPlan":{
        "id":"main-1F",
        "label":"본관 1층",
        "url":"https://hopes-v1.https.gsmsv.site/api/campus/floor-plans/main-1F/image",
        "width":844,
        "height":247,
        "floor":"1F",
        "group":"본관·실습동",
        "buildingId":1
      }
    }]
  }
}
```

설명용 예시이며 place.x/y는 문 또는 복도 접점이다. 방 전체는 area로 강조한다. 실제 응답의 label, x/y를 사용한다. 같은 이름의 장소가 여러 층에 있으면 `SELECT_DESTINATION`과 후보 목록을 반환한다. 앱은 후보 선택을 받아 `destinationPlaceId`로 보낸다. 장소가 없거나 위치 질문이 아니면 location은 null이다. 비공개 층은 후보에 나오지 않는다.

## 출발 위치가 있는 경로 요청

```json
{
  "content":"여기서 보건실까지 안내해줘",
  "destinationPlaceId":"main-1F-02",
  "navigationOrigin":"main-3F@120,104",
  "elevatorOnly":false,
  "clientRequestId":"고유한-요청-ID"
}
```

navigationOrigin은 기존 길찾기 API와 동일하게 장소 ID 또는 `도면ID@x,y`이다. GPS만으로 층을 판단하지 않는다. destinationPlaceId 없이도 질문에서 목적지가 하나로 결정되면 경로를 계산한다. 목적지가 없거나 여러 개면 400, 미공개/없는 장소는 404, 경로가 없으면 422다. elevatorOnly=true면 계단을 제외한다.

응답 assistant 메시지의 `location.status`는 `ROUTE_READY`, requiresOrigin은 false이며 `location.route`에 기존 길찾기 API와 동일한 from, to, legs, steps, distanceEstimated 등을 포함한다. 본관 3F→1F 경로는 필요한 두 층의 도면과 path를 제공한다. 계단으로 지나가는 중간 층 도면은 생략한다.

## 앱 표시

1. assistant 메시지의 location이 null이 아니면 본문 아래 도면 카드를 표시한다.
2. floorPlan.url을 **Bearer 인증 헤더로 다운로드**한다. 인증 없는 기본 이미지 로더만 사용하면 401이다.
3. 이미지는 가로세로 비율을 유지하고 실제 이미지가 그려진 영역에 맞춰 좌표를 변환한다. 좌우·상하 여백을 포함한 전체 카드 크기를 좌표 배율로 사용하면 안 된다.
4. area 사각형을 목적지 방에 표시하고, route가 있으면 각 leg의 floorPlan과 path를 순서대로 표시한다.
5. DESTINATION_READY는 목적지 도면만 보여 주고 출발 위치 선택을 요청한다. SELECT_DESTINATION은 후보 선택 UI를 표시한다.
6. 출발 위치와 경로는 응답에서만 제공한다. 재조회 시 목적지 카드만 남으며 출발 위치를 다시 받아야 한다.

목적지 카드만 DB에 저장한다. 출발 좌표와 계산 경로는 DB에 저장하지 않는다. 도면 카드가 연결된 위치 질문은 Discord 질문 로그에도 전송하지 않는다. 다만 사용자가 content에 직접 위치를 적으면 일반 대화 내용으로 저장될 수 있다. 동일 clientRequestId에 출발지·목적지·elevatorOnly가 달라지면 409다. 재시도 시 같은 요청에는 경로를 다시 계산해 반환한다.

이 서버 변경만으로 기존 앱에 지도 화면이 자동 생성되지는 않는다. 앱은 위 location 필드를 파싱하고 도면 카드를 렌더링해야 한다.

## 시설 명칭과 후속 질문

홈베이스는 사용자 현장 확인에 따라 본관 2·3·4층 중앙계단 앞 공용 공간으로 등록했다. ID는 `main-2F-homebase`, `main-3F-homebase`, `main-4F-homebase`다. 표시 사각형은 중앙계단 앞을 안내하기 위한 대략적인 영역이며 실측 경계가 아니다. 전체 위치 질문에는 세 후보를 반환하고, 층을 지정하면 해당 층만 반환한다. `4층은 없어?`처럼 층만 묻는 후속 질문은 최근 사용자 위치 질문을 이어받는다.

공개 자료에서 위치를 확인할 수 없는 홈베이스 또는 건물·층이 명시된 장소 질문에는 `location.status=LOCATION_UNVERIFIED`, 빈 candidates, 참고용 floorPlans와 message를 반환할 수 있다. 미등록은 실제 시설의 부재를 뜻하지 않는다. 이 응답에는 정확한 목적지 마커나 경로를 그리지 말고 확인 안내와 참고 도면만 표시한다. 본관 질문을 기숙사 층 정보로 대신 답하지 않는다.
