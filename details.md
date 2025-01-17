 ## Details 1. 확장성과 성능을 고려한 인증 및 인가

 Spring Security를 통해 인증서버 내부에 토큰저장소를 유지하여 인증 및 인가를 구현하였습니다. 하지만 하나의 인증서버 내부에서 토큰저장소를 관리하면, 두 개 이상의 서버를 같이 운영하며 사용할 수 없는 확장성 문제가 있었고, 휘발성이 있는 메인 메모리 내부에만 데이터를 유지하여 서버를 재시작할 때마다 데이터가 초기화되는 문제 등이 있었습니다. 이러한 문제들을 Redis 를 사용하여 해결하였습니다.

 #### 1.1 확장성을 고려한 토큰저장소

 토큰저장소가 인증서버 내부에 존재한다면 추후에 인증서버를 분리하거나 수평적으로 서버를 확장할 때 동기화 이슈가 발생합니다. 불필요한 동기화 작업대신 Redis를 통해 토큰저장소를 외부로 추출하여 여러 서버에서 효율적으로 데이터를 공유하도록 하였습니다.   

 #### 1.2 Redis 를 통한 조회성능 개선 및 Persistent

 저의 개발환경과 달리, 실제 서비스에서 인증 및 인가 절차에는 많은 쿼리가 발생할 것입니다. 조회가 필요할 때마다 DB를 통해 조회하는 것은 병목현상의 원인이 될 수 있다고 생각하였습니다. 따라서 불변값인 id값을 세션에 유지하여, 기존 메인 DB인 MySQL에서 조회하는 것이 아닌 유지하고있는 세션정보를 통해 조회하도록 하였습니다.

 또한, 물리적인 성능 향상을 위해 인메모리 기반의 솔루션을 통해 빠른성능에 대응하려고 했고, 그중에서도 오픈소스이며, 스프링의 `PSA`로 인하여 사용이 쉬운 Redis 를 선택하였습니다. 부가적으로 Redis는 `RDB` 및 `AOF` 기능을 통해 인메모리 데이터베이스에서도 데이터를 유지할 수 있었고 이는 서버의 장애로인해 메인 메모리 데이터가 손실되더라도 복구할 수 있는 장점 등을 활용할 수 있었습니다.


## Details 2. JPA와 트랜잭션을 활용한 주문시스템

 이전 프로젝트에는 단순한 로직에 대해서만 JPA를 적용하면서 JPA를 단순히 CRUD를 편하게 해주는 도구 정도로만 인식하고 있었습니다. JPA에 대해 깊게 공부하면서, 다양한 연관관계를 설계하고 여러 가지 문제를 접하면서 영속성 컨텍스트가 DB와 어플리케이션 사이에서 1차 캐시, 트랜잭션을 지원하는 쓰기지연, 더티 체킹, 지연 로딩 등 영속성 컨텍스트의 매커니즘에서 얻을 수 있는 이점들을 활용하였습니다.


#### 2.1 JPA 활용한 개발시간 단축 및 DB 부하 감소

주문객체는 다음과 같은 객체들을 포함하고있습니다.
사용자, 결제정보, 배송정보, 주문상품 리스트

 `@OneTomany`, `@ManyToOne` 어노테이션을 통해 연관관계를 설정하고 @JoinColumn 을 활용하여 주문객체를 통해 다른 테이블에 있는 정보들을 쉽게 조회할 수 있었습니다. 또한 [ 주문 <-> 배송 ] 과 같은 일대일 연관관계에서는 주문을 통해서도 배송을 조회하고, 배송을 통해서도 어떤 주문인지 알 수 있어야하기 때문에 양방향 매핑을 하였습니다. 이 때 양쪽에서 수정, 삭제가 발생하면 일관적이지 못하므로 `mappedBy` 옵션을 통해 연관관계의 주인을 설정하였습니다.     

 하지만, 주문객체를 불러올 때마다 주문객체에 포함된 모든 정보들을 조회하는 것은 DB에 필요이상의 부하가 생긴다고 생각하여, 객체들을 실제사용할 때만 쿼리가 발생하는 지연로딩을 적용하여 불필요한 조회쿼리의 발생을 줄였습니다. 

 또한, 주문이 없으면 결제정보와 배송정보 주문상품리스트는 생성되지 않습니다. 따라서 처음 주문객체가 생성될 때 결제, 배송, 주문상품도 동시에 생성되는 `cascade` 옵션을 활용하여 주문객체와 생명주기를 맞춰주었습니다.


#### 2.2 Native Query를 통한 벌크성 수정

 사용자가 상품을 주문하고 결제가 완료되면 주문한 상품의 재고를 감소시켜야 합니다. 이때 주문상품의 목록은 List로 되어있고 상품 하나하나를 For문을 통해 재고를 감소시키는 로직에는 문제가 있었습니다. 이는 JPA의 객체지향 쿼리인 JPQL 이 SQL로 변환되는 과정에서 우선적으로 영속성컨텍스트가 아닌 데이터베이스에서 검색하기 때문에 발생하는 문제입니다.
 
 List에 담겨있는 상품들에 대해서 재고감소 메서드를 호출할 때마다 상품을 수정하는 쿼리가 중복적으로 발생하였습니다. 글로벌 패치 전략인 즉시, 지연 로딩에 관계없이 N+1 문제가 발생하였고, 한번의 주문에 많은 상품을 구입하는 사용자들이 몰릴경우에는 DB에 부하가 심해져 서비스가 느려지거나 장애가 발생할 수 있습니다.
 
 이에 벌크성 수정 및 삭제를 위해 `@Modifying` 어노테이션을 사용하였고, `@Query` 를 통해 직접 SQL 을 작성하여 JPA에서도 Native Query 로 문제를 해결할 수 있었습니다. 결과적으로 N개의 상품에 대해 N+1 개의 쿼리가 발생하던 상황을, 주문상품 리스트를 불러오는 조회 쿼리와 상품리스트에서 아이템 재고를 수정하는 쿼리 2개로 줄일 수 있었습니다.


#### 2.3 주문, 결제, 상품 서비스의 트랜잭션 처리를 통한 일관성있는 데이터처리

상품을 주문하고 결제를 완료하면 다음과 같은 일들이 벌어집니다.

1. 해당하는 주문을 조회
2. 결제 상태를 변경하고 결제정보를 DB에 저장
3. 주문한 상품들의 재고를 감소
4. 각 결제 수단별로 부가적인 정보를 DB에 저장

 2번에 해당하는 내용을 수행하고 재고를 감소하는 3번에서 에러가 발생하여 실행되지 않았다면, 추후에 DB에는 재고가 남아있어 상품을 계속 구매할 수 있지만 실제 재고는 소진되는 문제 등이 발생할 수 있습니다.

 원자성과 일관성을 유지하기 위해 스프링에서 제공하는 선언적 트랜잭션방법인 `@Transactional` 어노테이션을 통해 트랜잭션을 적용하였습니다. 이를 통해 프록시로 핵심기능에 접근방식을 제어하는 프록시패턴과, 부가기능을 위임하는 데코레이터 패턴을 활용할 수 있었고, 다이나믹 프록시와 프록시 팩토리 빈을 통해 어노테이션만으로 트랜잭션의 경계안에 1 ~ 4 과정을 포함시킬 수 있었습니다.
 
 
 ## Details 3. 다양한 결제수단을 일관적인 방법으로 사용하기 위한 결제서비스 추상화

 주문에 대한 결제는 여러가지 방법이 존재합니다. 많은 결제 수단에도 일관된 방법으로 결제완료, 결제취소를 진행하고, 결제정보를 하나의 테이블로 관리하기 위해 인터페이스를 정의하여 결제서비스를 추상화하였습니다.


#### 3.1 재사용성을 고려한 인터페이스 추출

 각 결제수단은 ‘주문에 대한 결제’ 라는 비슷한 성격을 가지고 있어서 일정한 흐름이 존재합니다. 공통적으로 변하지 않는 부분 사이에 개별적인 로직이 존재하는 구조입니다. n개의 구현클래스마다 n번의 중복코드가 반복되는 공통부분을 효율적으로 재사용하기 위해 개별적인 로직만을 인터페이스로 추출하여 정의하였고 변하지 않는 부분을 하나의 메서드로 정의하였습니다.

 결제수단에 따라 변하는 핵심로직을 파라미터를 통해 외부에서 주입하도록 구현하였습니다. 이를 통해 비즈니스 로직을 담당하는 결제서비스는 인터페이스에만 의존하도록 설계할 수 있었고, 이는 기존의 코드변경없는 새로운 결제방법의 추가를 가능하게 하였습니다.


#### 3.2 팩토리를 통한 책임분리

 하위 결제 서비스를 주입할 때에는 구현타입을 결정하는 팩토리 클래스를 통해 주입하였습니다. 이는 컨트롤러에서 클라이언트로부터 받는 결제타입에 따라 서비스 구현체를 결정하는 책임과 기존 클라이언트의 요청을 서비스에게 전달하는 컨트롤러의 책임을 분리하기 위함입니다.

 팩토리 클래스는 결제타입에따라 분기하는 형태가 아닌 `HashMap` 자료구조를 통해 구현하였습니다. 이를 통해 결제가 추가될 때마다 조건문에 추가하지 않고 Map 에 추가하는 방법으로 코드를 간결하게 줄일 수 있었습니다. 또한, `ImmutableCollections` 클래스를 통해 해당 Map은 생성될 때를 제외하고 읽기만 가능하도록 하였습니다.
 
 
 ## Details 4. Redis를 이용한 장바구니 서비스

 장바구니는 물건을 구입하기전에 여러상품을 담아서 관리하기위한 용도로 사용합니다. 이는 실제 구매를 하지않는 사용자도 이용할 수 있는 서비스특성상 구매 요청이상의 요청이 발생할 수 있다고 생각했고, 임시적으로 보관하는 데이터를 실시간적으로 DB에 유지하는 것은 적절하지 않다고 판단하였습니다. 

 빠른 서비스를 위해 Redis를 Key-value 스토어로 활용하였고, 장바구니의 모든 필드들을 Hash-key 로 하여 상품정보들을 Redis가 지원하는 Hash 자료구조 형태로 저장하였습니다. 이를 통해, 장바구니 상품의 조회성능을 높일 수 있었고, 트랜잭션의 경계설정을 통해 동시에 여러 요청에 대하여 안전한 트랜잭션을 보장하도록 설계하였습니다.
  

 #### 4.1 Netty 기반 Lettuce 를 통한 고성능 데이터처리

 Spring Data Redis 는 서비스 추상화를 통해 자바의 Redis Client 오픈소스 중 하나이고, 비동기 이벤트 처리기반 고성능의 Lettuce 사용을 쉽고 간단하게 제공합니다. 이에 `RedisConnectionFactory` 로 Lettuce 를 등록하고 직렬화 및 역직렬화하는 설정을 통해 간단하게 연결하였습니다.
 
 이후 `@RedisHash` 를 통해 도메인 클래스를 Entity 로 등록하고, 레포지토리는 스프링 데이터의 `CrudRepository`를 상속하여 기본 CRUD 메서드를 통해 간단하면서도 기존 MySQL에 비해 빠른 처리속도로 구현할 수 있었습니다.

 #### 4.2 고립성을 위한 안전한 트랜잭션 처리

 Spring Data가 제공하는 추상화된 레포지토리를 이용하여 서비스를 쉽게 개발하였지만, 공유자원에 대하여 하나의 트랜잭션이 다른 트랜잭션에 영향을 끼치는 문제를 발견하여 안전한 트랜잭션을 보장하지 못하였습니다.

 `MULTI` 명령어를 통해 트랜잭션 경계를 설정하고, Optimistic Locking 기반의 `WATCH` 명령어를 통해 공유자원에 대한 동시성 이슈를 감지할 수 있었습니다. 이후 `DISCART` 명령어를 통해 해당 예외상황을 롤백시켜 트랜잭션의 고립성을 충족시킬 수 있었습니다. 
 
## More 1. 테스트 자동화
 
 
#### 1.1 단위테스트 코드 작성
 
   이전 프로젝트에서는 통합 테스트 코드만을 통해 비즈니스 로직을 검증하였었는데, 테스트에 오류가 발생했을 때 오류가 발생하는 곳을 찾아야 하는 문제, 테스트시간이 오래걸린다는 문제 등이 있었습니다. 각자의 책임과 역할이 있는 계층 구조에서 통합테스트 이전에 단위테스트를 통해 검증해야 함을 알게 되었습니다.  
 
  해당 클래스를 고립시키기위해 다른 의존관계를 mocking 하여 특정 값을 리턴하도록 설정함으로써 테스트하고자 하는 로직만을 검증할 수 있었습니다. 스프링 부트를 동작시키지 않는 단위테스트를 진행하였기 때문에 기존 통합테스트에 비해 로직을 검증하는 데에 많은 시간을 단축할 수 있었고, 특정 클래스로 세분화하였기 때문에 오류가 발생하여도 쉽게 수정할 수 있었습니다.
 
 
#### 1.2 Continuous Integration
 
  매번 코드를 작성하고 커밋할 때에  기존의 테스트 코드를 통해 자동으로 검증되게 하여 무결성을 보장하는 시간을 단축하였습니다.
  
  •네이버 클라우드 플랫폼의 micro 서버에 jenkins를 설치
  •-Dtest 명령어를 통해 필요한 테스트만 적용함으로써 테스트시간 단축
  •github hook을 통해 커밋할 때마다 테스트가 자동으로 수행
 
 위 과정에서 JDK 문제, Jenkins의 버전문제로 인한 plug-in 오류, 서버의 포트 문제 등의 이슈가 있었습니다. 이런 이슈들을 해결하며 개발 이외에 인프라를 공부하는 계기가 되었습니다. 결과적으로 테스트 자동화 서버를 구축하여 수동으로 행해지는 테스트과정에서의 실수를 줄일 수 있었고, Continuous Integration에 대해서 학습할 수 있었습니다.
 
 

## More 2. JPA Entity 은닉화

 JPA 의 영속성 컨텍스트는 엔티티의 변경을 감지하여 초기 스냅샷과 비교하여 변경된 값을 DB에 반영합니다. 따라서 어떤 클래스든 엔티티에 접근할 수 있다면 DB 값을 변경 할 여지가 있었습니다. 프로젝트규모가 커지면서 많은 개발자들이 협업을 할 때에
엔티티가 여러 클래스에 넓게 퍼져있어 세터를 호출하면 DB 값이 바뀔 수 있습니다. 이를 방지하기 위해 은닉화를 적용하였습니다.
 
 그래서 데이터 접근의 책임을 해당 서비스로 국한하기 위해 메시지를 주고받는 다른 클래스에서는 Data Transfer Object를 활용하였습니다.  

#### 2.1 DTO 적용

 데이터의 변경 책임이 있는 서비스를 제외한 다른 클래스에서는 엔티티가 아닌 DTO를 통해 메시지를 주고 받았고. 해당 서비스에서는 받아온 DTO를 엔티티로 변경하여 비즈니스 로직을 처리하고 다른 클래스로 리턴할 때에는 다시 DTO로 변경하여 보내주었고, 결과적으로 해당 서비스를 제외한 다른 클래스에서는 엔티티의 의존성을 제거할 수 있었습니다.

#### 2.2 순환참조 문제 해결

 엔티티로  변경하는 로직을 DTO에, DTO 로 변경하는 로직을 엔티티에 두는 구조는 서로를 참조하고 있는 순환참조 구조이고 이는 강한 결합력으로 되어있어 구조적으로 용이하지 못합니다. Entity 와 DTO 중간에서 두 객체의 스왑을 위한 어댑터를 활용하여 순환참조 구조를 해결하였습니다.

 위 과정들을 통해서 엔티티를 은닉화하여 의도하지 않게 데이터를 변경할 위험을 최소화 시켰고, 이후 비즈니스 로직을 추가하거나 변경하더라도 담당 서비스를 제외한 다른 클래스 에서는 직접 의존하지 않아 확장성에 용이한 구조로 설계하였습니다.


   
