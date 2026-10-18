-- The name index: which repository id a project-scoped name resolves to, held HERE.
--
-- Until now this host remembered no name. `/git/<projectId>/<repoName>` asked qits-projects on every
-- request (HttpRepositoryNameResolver), so the public clone url of every repository on the platform
-- served only while qits-projects did. qits-projects still OWNS the names — it is the one writer, and
-- it fills these tables through `PUT /git/<repoId>/names`, one repository's whole name set per call —
-- and this host keeps the copy it answers from (persistence/RepositoryNameStore).
--
-- A REPOSITORY'S NAME SET IS REPLACED WHOLE, AND ORDERED BY ITS REVISION. Each PUT carries the set
-- and a revision qits-projects increases; a PUT whose revision is not newer than the stored one
-- changes nothing, so a retried or reordered delivery can never roll a rename back. The revision row
-- exists from a repository's first PUT on — an empty set included — and is what the write locks, so
-- two PUTs for one repository serialise on it.
--
-- A NAME MOVES; IT IS NEVER SHARED. (project_id, name) is the key, so a PUT naming a name another
-- repository of the same project holds takes it over: the newer statement of the estate wins.
-- Names are case-sensitive, exactly as the url segment is.
--
-- `git_project` answers the slug a project is also addressed by (`/git/qits/<name>`). A slug is held
-- by one project at a time: a PUT stating it for another project moves it. Rows stay when a
-- repository is deleted — a slug describes the project, not the repository.
--
-- Nothing here is a foreign key into anything, per this schema's rule: repository_id is the same
-- opaque string the pack tables hold, and project_id is qits-projects' id, which this schema knows
-- nothing about. DELETE /git/<repoId> takes the repository's name and revision rows with its packs
-- (RepositoryPurge).
create table git_repository_name (
    project_id    varchar(64)  not null,
    name          varchar(255) not null,
    repository_id varchar(64)  not null,
    primary key (project_id, name)
);

create index ix_git_repository_name_repository on git_repository_name (repository_id);

create table git_repository_names_revision (
    repository_id varchar(64) primary key,
    project_id    varchar(64) not null,
    revision      bigint      not null,
    updated_at    timestamptz not null
);

create table git_project (
    project_id varchar(64)  primary key,
    slug       varchar(255) not null,
    constraint uk_git_project_slug unique (slug)
);
