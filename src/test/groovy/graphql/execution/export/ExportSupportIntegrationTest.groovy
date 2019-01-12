package graphql.execution.export

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.TestUtil
import graphql.execution.AsyncSerialExecutionStrategy
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.RuntimeWiring
import graphql.validation.ValidationError
import graphql.validation.ValidationErrorType
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.stream.Collectors

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring

class ExportSupportIntegrationTest extends Specification {
    class Post {
        public String id
        public String text
        public List<Comment> comments
        public List<Tag> tags

        Post(String id, String text) {
            this.id = id
            this.text = text
            this.comments = new ArrayList<>()
            this.tags = new ArrayList<>()
        }

        void addComment(Comment comment) {
            this.comments.add(comment)
        }

        void addTag(Tag tag) {
           this.tags.add(tag)
        }
    }

    class Comment {
        public String id
        public String text
        public Post post

        Comment(String id, Post post, String text) {
            this.id = id
            this.post = post
            this.text = text
        }
    }

    class Tag {
        public String id
        public String name

        Tag(String id, String name) {
            this.id = id
            this.name = name
        }
    }

    def schemaSpec = '''
            schema {
                query: Query
                mutation: Mutation
            }

            type Query {
            }

            type Mutation {
                createPost(text: String!): Post
                createComment(postId: ID!, text: String!): Comment
                createTag(name: String!): Tag
                tagPost(postId: ID!, tagIds: [ID!]!): [Tag!]
            }

            type Post {
                id: ID!
                text: String!
                comments: [Comment]
            }

            type Comment {
                id: ID!
                post: Post!
                text: String!
            }
            
            type Tag {
                id: ID!
                name: String!
            }
        '''

    List<Post> posts = []
    List<Comment> comments = []
    List<Tag> tags = []

    Post getPost(Object postId) {
        return posts.stream()
                .filter { post -> (post.id == (String) postId) }
                .findFirst()
                .get()
    }

    List<Tag> getTags(List<Object> tagIds) {
        return tags.stream()
                .filter { tag -> tagIds.contains((Object) tag.id) }
                .collect(Collectors.toList())
    }

    DataFetcher postFetcher = new DataFetcher() {
        @Override
        Object get(DataFetchingEnvironment env) {
            return CompletableFuture.supplyAsync({
                Post post = new Post("POST_" + posts.size(), (String) env.getArgument("text"))
                posts.add(post)
                return post
            })
        }
    }
    DataFetcher commentsFetcher = new DataFetcher() {
        @Override
        Object get(DataFetchingEnvironment env) {
            return CompletableFuture.supplyAsync({
                Post post = getPost(env.getArgument("postId"))
                Comment comment = new Comment("COMMENT_" + comments.size(), post, (String) env.getArgument("text"))
                post.addComment(comment)
                return comment
            })
        }
    }

    DataFetcher tagsFetcher = new DataFetcher() {
        @Override
        Object get(DataFetchingEnvironment env) {
            Tag tag = new Tag("TAG_" + tags.size(), (String) env.getArgument("name"))
            tags.add(tag)
            return tag
        }
    }

    DataFetcher tagPostFetcher = new DataFetcher() {
        @Override
        Object get(DataFetchingEnvironment env) {
            Post post = getPost(env.getArgument("postId"))
            List<Tag> tags = getTags((List<Object>) env.getArgument("tagIds"))
            tags.forEach(post.&addTag)
            return tags
        }
    }

    GraphQL graphQL = null

    void setup() {
        def runtimeWiring = RuntimeWiring.newRuntimeWiring()
                .type(newTypeWiring("Mutation").dataFetcher("createPost", postFetcher))
                .type(newTypeWiring("Mutation").dataFetcher("createComment", commentsFetcher))
                .type(newTypeWiring("Mutation").dataFetcher("createTag", tagsFetcher))
                .type(newTypeWiring("Mutation").dataFetcher("tagPost", tagPostFetcher))
                .build()

        graphQL = TestUtil.graphQL(schemaSpec, runtimeWiring)
                .mutationExecutionStrategy(new AsyncSerialExecutionStrategy())
                .build()

        posts.clear()
        comments.clear()
    }

    def "test exporting a single variable"() {
        def query = '''
            mutation PostAndComment($postText: String!, $commentText: String!) {
                createPost(text: $postText) {
                    id @export(as: "postId")
                    text
                }

                createComment(postId: $postId, text: $commentText) {
                    id
                    post { id }
                    text
                }
            }
        '''

        when:
        def variables = new HashMap<>()
        variables.put("postText", "My first post")
        variables.put("commentText", "My first comment")

        def result = graphQL.execute(ExecutionInput.newExecutionInput().query(query).variables(variables).build())

        then:
        result.errors.isEmpty()
        result.data == [
                "createPost": ["id": "POST_0", "text": "My first post"],
                "createComment": ["id": "COMMENT_0", "post": ["id": "POST_0"], "text": "My first comment"]
        ]
    }

    def "test exporting a single variable out of order"() {
        def query = '''
            mutation PostAndComment($postText: String!, $commentText: String!) {
                createComment(postId: $postId, text: $commentText) {
                    id
                    post { id }
                    text
                }
                
                createPost(text: $postText) {
                    id @export(as: "postId")
                    text
                }
            }
        '''

        when:
        def variables = new HashMap<>()
        variables.put("postText", "My first post")
        variables.put("commentText", "My first comment")

        def result = graphQL.execute(ExecutionInput.newExecutionInput().query(query).variables(variables).build())

        then:
        result.errors.size() == 1
        result.data == null

        def error = (ValidationError) result.errors.get(0)
        error.getValidationErrorType() == ValidationErrorType.UndefinedVariable
    }

    def "test exporting multiple variables into a list"() {
        def query = '''
            mutation TagPost($postText: String!) {
                tag0: createTag(name: "tag0") {
                    id @export(into: "tagIds")
                }
                
                tag1: createTag(name: "tag1") {
                    id @export(into: "tagIds")
                }
                
                createPost(text: $postText) {
                    id @export(as: "postId")
                }
                
                tagPost(postId: $postId, tagIds: $tagIds) {
                    name
                }
            }
        '''

        when:
        def variables = new HashMap<>()
        variables.put("postText", "My first post")

        def result = graphQL.execute(ExecutionInput.newExecutionInput().query(query).variables(variables).build())

        then:
        result.errors.isEmpty()
        result.data == [
                "tag0": ["id": "TAG_0"],
                "tag1": ["id": "TAG_1"],
                "createPost": ["id": "POST_0"],
                "tagPost": [["name": "tag0"], ["name": "tag1"]]
        ]
    }
}
