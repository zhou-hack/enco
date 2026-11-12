<#
.SYNOPSIS
    Fetch the reference projects into upstream/ (git-ignored, read-only).

.DESCRIPTION
    upstream/ holds Mika, dsh-go and humanize-agent for design reference only.
    It is ignored by git, so a fresh clone of enco does not contain it; run this
    script once after cloning. Code from upstream/ must never be copied into enco
    (see AGENTS.md section 8).

    By default every project is checked out at the pinned commit below, so both
    team members read exactly the same version.

.PARAMETER Latest
    Check out the tip of each project's tracked branch instead of the pinned commit.

.PARAMETER Update
    Re-sync projects that already exist (by default they are left untouched).

.EXAMPLE
    .\scripts\init-upstream.ps1
    .\scripts\init-upstream.ps1 -Update
    .\scripts\init-upstream.ps1 -Update -Latest
#>
param(
    [switch]$Latest,
    [switch]$Update
)

$ErrorActionPreference = 'Stop'

$repos = @(
    @{ Name = 'Mika';           Url = 'https://github.com/wangzan101/Mika.git';           Branch = 'main';                Commit = '0c9e27d1ee3df3424aa58f9cc86b243d5ad641b5' }
    @{ Name = 'dsh-go';         Url = 'https://github.com/ccch1mneyyy/dsh-go.git';        Branch = 'main';                Commit = 'ec32775a412ffb126594fcfabd160df158f199d6' }
    @{ Name = 'humanize-agent'; Url = 'https://github.com/zhou-hack/humanize-agent.git'; Branch = 'feature/schoolagent'; Commit = 'b70d9319f1904373fa811c8f2e40400169a1a0e8' }
)

function Invoke-Git {
    # Plain function on purpose: $args passes tokens such as -C and --quiet straight to git.
    & git @args
    if ($LASTEXITCODE -ne 0) {
        throw "git $($args -join ' ') failed with exit code $LASTEXITCODE"
    }
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw 'git was not found in PATH.'
}

$upstream = Join-Path (Split-Path -Parent $PSScriptRoot) 'upstream'
New-Item -ItemType Directory -Force -Path $upstream | Out-Null

$failed = @()
foreach ($repo in $repos) {
    $dir = Join-Path $upstream $repo.Name
    $ref = if ($Latest) { $repo.Branch } else { $repo.Commit }

    try {
        if (Test-Path (Join-Path $dir '.git')) {
            if (-not $Update) {
                $head = (& git -C $dir rev-parse --short HEAD)
                Write-Host "[skip]  $($repo.Name) already present at $head (use -Update to re-sync)"
                continue
            }
            Invoke-Git -C $dir remote set-url origin $repo.Url
        }
        elseif (Test-Path $dir) {
            throw "$dir exists but is not a git repository; delete it and run again."
        }
        else {
            Invoke-Git init --quiet $dir
            Invoke-Git -C $dir remote add origin $repo.Url
        }

        Write-Host "[fetch] $($repo.Name) @ $ref"
        # A full clone stays full; only shallow (or new) repositories fetch with --depth 1.
        $shallow = (& git -C $dir rev-parse --is-shallow-repository) -eq 'true'
        $isNew = -not (& git -C $dir rev-parse --verify --quiet HEAD)
        $fetchArgs = @('-C', $dir, 'fetch', '--quiet')
        if ($shallow -or $isNew) { $fetchArgs += @('--depth', '1') }
        $fetchArgs += @('origin', $ref)
        # GitHub connections drop now and then on campus networks; retry before giving up.
        for ($attempt = 1; ; $attempt++) {
            try { Invoke-Git @fetchArgs; break }
            catch {
                if ($attempt -ge 3) { throw }
                Write-Host "[retry] $($repo.Name) fetch failed (attempt $attempt of 3), retrying..."
                Start-Sleep -Seconds (3 * $attempt)
            }
        }
        Invoke-Git -C $dir checkout --quiet --detach FETCH_HEAD

        $head = (& git -C $dir rev-parse --short HEAD)
        Write-Host "[ok]    $($repo.Name) at $head"
    }
    catch {
        Write-Warning "$($repo.Name): $($_.Exception.Message)"
        $failed += $repo.Name
    }
}

if ($failed.Count -gt 0) {
    Write-Host "Failed: $($failed -join ', '). Check the network and run the script again." -ForegroundColor Red
    exit 1
}
Write-Host "upstream/ is ready. Read it for design ideas only; never copy its code into enco."
